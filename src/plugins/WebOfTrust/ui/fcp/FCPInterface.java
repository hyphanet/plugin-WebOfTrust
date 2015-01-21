/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.fcp;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import plugins.WebOfTrust.EventSource;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.SubscriptionManager.BeginSynchronizationNotification;
import plugins.WebOfTrust.SubscriptionManager.EndSynchronizationNotification;
import plugins.WebOfTrust.SubscriptionManager.IdentitiesSubscription;
import plugins.WebOfTrust.SubscriptionManager.IdentityChangedNotification;
import plugins.WebOfTrust.SubscriptionManager.Notification;
import plugins.WebOfTrust.SubscriptionManager.ObjectChangedNotification;
import plugins.WebOfTrust.SubscriptionManager.ScoreChangedNotification;
import plugins.WebOfTrust.SubscriptionManager.ScoresSubscription;
import plugins.WebOfTrust.SubscriptionManager.Subscription;
import plugins.WebOfTrust.SubscriptionManager.SubscriptionExistsAlreadyException;
import plugins.WebOfTrust.SubscriptionManager.TrustChangedNotification;
import plugins.WebOfTrust.SubscriptionManager.TrustsSubscription;
import plugins.WebOfTrust.SubscriptionManager.UnknownSubscriptionException;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NoSuchContextException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.exceptions.UnknownPuzzleException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;
import plugins.WebOfTrust.introduction.IntroductionPuzzle.PuzzleType;
import plugins.WebOfTrust.introduction.IntroductionPuzzleStore;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.SubscriptionType;
import plugins.WebOfTrust.util.RandomName;
import freenet.clients.fcp.FCPPluginConnection;
import freenet.clients.fcp.FCPPluginMessage;
import freenet.keys.FreenetURI;
import freenet.node.FSParseException;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;

/**
 * ATTENTION: There is a deprecation mechanism for getting rid of old SimpleFieldSet keys (fields)
 * in FCP messages sent by WOT - which can be enabled by setting the {@link Logger.LogLevel} to
 * {@link LogLevel#MINOR} for this class:<br>
 * - If a {@link FCPPluginMessage} sent by WOT contains a value of "SomeField.DeprecatedField=true"
 *   in the {@link FCPPluginMessage#params}, then you should not write new client code to use the
 *   field "SomeField". A wildcard of "*" to match any characters can also be valid in the key name.
 *   <br>
 * - A value of "SomeField.DeprecatedField=false" can be used to exclude a field from the
 *   deprecation list if a wildcard "*" in "abc*abc.DeprecatedField=true matches more than desired.
 *   <br>
 * - If you want to change WOT to deprecate a certain field, use:<br>
 *   <code>if(logMINOR) aSimpleFieldSet.put("SomeField.DeprecatedField", true);</code><br>
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public final class FCPInterface implements FredPluginFCPMessageHandler.ServerSideFCPMessageHandler {

    /**
     * Timeout when sending an {@link SubscriptionManager.Notification} to a client.<br>
     * When this expired, deploying of the notification is considered as failed, and the
     * {@link SubscriptionManager} is notified about that. It then may re-sent it or terminate the
     * {@link Subscription} upon repeated failure.
     */
    public static final int SUBSCRIPTION_NOTIFICATION_TIMEOUT_MINUTES = 1;

    private final WebOfTrust mWoT;
    
    private final PluginRespirator mPluginRespirator;
    
    private final SubscriptionManager mSubscriptionManager;

    /** Automatically set to true by {@link Logger} if the log level is set to
     *  {@link LogLevel#MINOR} for this class.<br>
     *  Used as performance optimization to prevent construction of the log strings if it is not
     *  necessary. */
    private static transient volatile boolean logMINOR = false;

    static {
        // Necessary for automatic setting of logDEBUG and logMINOR
        Logger.registerClass(FCPInterface.class);
    }


    public FCPInterface(final WebOfTrust myWoT) {
        mWoT = myWoT;
        mPluginRespirator = mWoT.getPluginRespirator();
        mSubscriptionManager = mWoT.getSubscriptionManager();
    }
    
    /** TODO: Could be removed, is empty. */
    public void start() {}
    
    public void stop() {
        // We currently do not have to interrupt() threads on functions of FCPInterface which use
        // FCPPluginConnection.sendSynchronous():
        // By their JavaDoc, they all require the caller to deal with interrupting the thread upon
        // shutdown and all callers are outside of this class (they're typically in
        // SubscriptionManager).
    }

    /** {@inheritDoc} */
    @Override
    public FCPPluginMessage handlePluginFCPMessage(
            FCPPluginConnection connection, FCPPluginMessage fcpMessage) {
        
        if(fcpMessage.isReplyMessage()) {
            Logger.warning(this, "Received an unexpected reply message: WOT currently should only "
                + "use FCPPluginConnection.sendSynchronous() for anything which is replied to by "
                + "the client. Thus, all replies should be delivered to sendSynchronous() instead "
                + "of the asynchronous message handler. Maybe the "
                + "sendSynchronous() thread timed out already? Reply message = " + fcpMessage);
            return null;
        }
        

        final SimpleFieldSet params = fcpMessage.params;
        SimpleFieldSet result = null;
        FCPPluginMessage reply = null;
        
        try {
            final String message = params.get("Message");
            // TODO: Optimization: This should use a HashMap<String, HandleInterface> instead of zillions of equals()
            
            if (message.equals("GetTrust")) {
                result = handleGetTrust(params);
            } else if(message.equals("GetScore")) {
                result = handleGetScore(params);
            }else if (message.equals("CreateIdentity")) {
                result = handleCreateIdentity(params);
            } else if (message.equals("SetTrust")) {
                result = handleSetTrust(params);
            } else if (message.equals("RemoveTrust")) {
                result = handleRemoveTrust(params);
            } else if (message.equals("AddIdentity")) {
                result = handleAddIdentity(params);
            } else if (message.equals("GetIdentity")) {
                result = handleGetIdentity(params);
            } else if (message.equals("GetOwnIdentities")) {
                result = handleGetOwnIdentities(params);
            } else if (message.equals("GetIdentities")) {
                reply = handleGetIdentities(fcpMessage);
            } else if (message.equals("GetTrusts")) {
                reply = handleGetTrusts(fcpMessage);
            } else if (message.equals("GetScores")) {
                reply = handleGetScores(fcpMessage);
            } else if (message.equals("GetIdentitiesByScore")) {
                result = handleGetIdentitiesByScore(params);
            } else if (message.equals("GetTrusters")) {
                result = handleGetTrusters(params);
            } else if (message.equals("GetTrustersCount")) {
                result = handleGetTrustersCount(params);
            } else if (message.equals("GetTrustees")) {
                result = handleGetTrustees(params);
            } else if (message.equals("GetTrusteesCount")) {
                result = handleGetTrusteesCount(params);
            } else if (message.equals("AddContext")) {
                result = handleAddContext(params);
            } else if (message.equals("RemoveContext")) {
                result = handleRemoveContext(params);
            } else if (message.equals("SetProperty")) {
                result = handleSetProperty(params);
            } else if (message.equals("GetProperty")) {
                result = handleGetProperty(params);
            } else if (message.equals("RemoveProperty")) {
                result = handleRemoveProperty(params);
            } else if (message.equals("GetIntroductionPuzzles")) {
                result = handleGetIntroductionPuzzles(params);
            } else if (message.equals("GetIntroductionPuzzle")) {
                result = handleGetIntroductionPuzzle(params);
            } else if (message.equals("SolveIntroductionPuzzle")) {
                result = handleSolveIntroductionPuzzle(params);
            } else if (message.equals("Subscribe")) {
                reply = handleSubscribe(connection, fcpMessage);
            } else if (message.equals("Unsubscribe")) {
                reply = handleUnsubscribe(fcpMessage);
            } else if (message.equals("Ping")) {
                result = handlePing();
            } else if (message.equals("RandomName")) {
                result = handleRandomName(params);
            } else {
                throw new Exception("Unknown message (" + message + ")");
            }
            
            // All handlers throw upon error, so at this point, the call has succeeded and the
            // FCPPluginMessage reply should be available.
            // But some of the handlers still return the SimpleFieldSet result instead of a
            // FCPPluginMessage, so we must check whether the FCPPluginMessage reply was constructed
            // yet and construct it if not.
            if(reply == null && result != null) {
                reply = FCPPluginMessage.constructReplyMessage(
                    fcpMessage, result, null,
                    true,
                    null, null);
            }
        } catch (final Exception e) {
        	// TODO: This might miss some stuff which are errors. Find a better way of detecting which exceptions are okay.
            // A good solution would be to have the message handling functions return a valid
            // FCPPluginMessage with a proper errorCode field for errors which they know can happen
            // regularly such as the below ones. Then they wouldn't throw for those regular
            // errors, and this dontLog flag could be removed.
            // This will require changing the message handling functions to return FCPPluginMessage
            // instead of SimpleFieldSet though.
        	boolean dontLog = e instanceof NoSuchContextException ||
        						e instanceof NotInTrustTreeException ||
        						e instanceof NotTrustedException ||
        						e instanceof UnknownIdentityException ||
        						e instanceof UnknownPuzzleException;
        	
        	if(!dontLog)
        		Logger.error(this, "FCP error", e);
        	
        	
            reply = errorMessageFCP(fcpMessage, e);
        }
        
        return reply;
    }
    
    private String getMandatoryParameter(final SimpleFieldSet sfs, final String name) throws InvalidParameterException {
    	final String result = sfs.get(name);
    	if(result == null)
    		throw new IllegalArgumentException("Missing mandatory parameter: " + name);
    	
    	return result;
    }

    private SimpleFieldSet handleCreateIdentity(final SimpleFieldSet params)
    	throws InvalidParameterException, FSParseException, MalformedURLException {
    	
    	final String identityNickname = getMandatoryParameter(params, "Nickname");
    	final String identityContext = getMandatoryParameter(params, "Context");
    	final String identityPublishesTrustListStr = getMandatoryParameter(params, "PublishTrustList");
    	
    	final boolean identityPublishesTrustList = identityPublishesTrustListStr.equals("true") || identityPublishesTrustListStr.equals("yes");
    	final String identityInsertURI = params.get("InsertURI");

        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "IdentityCreated");

        // TODO: Performance: The synchronized() can be removed after this is fixed:
        // https://bugs.freenetproject.org/view.php?id=6247
        synchronized(mWoT) {
        OwnIdentity identity;

        if (identityInsertURI == null) {
            identity = mWoT.createOwnIdentity(identityNickname, identityPublishesTrustList, identityContext);
        } else {
            identity = mWoT.createOwnIdentity(new FreenetURI(identityInsertURI), identityNickname, identityPublishesTrustList,
            		identityContext);
        }
   
        try {
			mWoT.setPublishIntroductionPuzzles(identity.getID(), params.getBoolean("PublishIntroductionPuzzles", false));
		} catch (UnknownIdentityException e) {
			throw new RuntimeException(e);
		}

        sfs.putOverwrite("ID", identity.getID());
        sfs.putOverwrite("InsertURI", identity.getInsertURI().toString());
        sfs.putOverwrite("RequestURI", identity.getRequestURI().toString());
        }

        return sfs;
    }
    
    private SimpleFieldSet handleGetTrust(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String trusterID = getMandatoryParameter(params, "Truster");
    	final String trusteeID = getMandatoryParameter(params, "Trustee");
    	
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
        // TODO: Performance: The synchronized() can be removed after this is fixed:
        // https://bugs.freenetproject.org/view.php?id=6247
    	synchronized(mWoT) {
            // getTrust() won't validate the IDs. Since we are a UI, it's better to do it:
            // This will prevent getTrust() claiming that there is no trust due to invalid IDs.
            IdentityID.constructAndValidateFromString(trusterID);
            IdentityID.constructAndValidateFromString(trusteeID);

    		Trust trust = null;
    		try {
                trust = mWoT.getTrust(trusterID, trusteeID);
    		} catch(NotTrustedException e) {}
    		
    		handleGetTrust(sfs, trust, "0");
    	}
    	sfs.putOverwrite("Message", "Trust");
    	return sfs;
    }
    
    private SimpleFieldSet handleGetTrust(final SimpleFieldSet sfs, final Trust trust, String suffix) {
    	final String prefix = "Trusts." + suffix + ".";
    	
    	if(trust == null) {
    		sfs.putOverwrite(prefix + "Value", "Nonexistent");
    		return sfs;
    	}
    	
		sfs.putOverwrite(prefix + "Truster", trust.getTruster().getID());
		sfs.putOverwrite(prefix + "Trustee", trust.getTrustee().getID());
		sfs.putOverwrite(prefix + "Value", Byte.toString(trust.getValue()));
		sfs.putOverwrite(prefix + "Comment", trust.getComment());
		sfs.put(prefix + "TrusterEdition", trust.getTrusterEdition());
		sfs.putOverwrite(prefix + "VersionID", trust.getVersionID().toString());
		
    	sfs.putOverwrite("Trusts.Amount", "1");
    	
		return sfs;
    }
    
    private SimpleFieldSet handleGetScore(final SimpleFieldSet params) throws UnknownIdentityException, InvalidParameterException {
    	final String trusterID = getMandatoryParameter(params, "Truster");
    	final String trusteeID = getMandatoryParameter(params, "Trustee");

    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
        // TODO: Performance: The synchronized() can be removed after this is fixed:
        // https://bugs.freenetproject.org/view.php?id=6247
    	synchronized(mWoT) {
    		Score score = null;
    		try {
        		// TODO: Optimize by implementing https://bugs.freenetproject.org/view.php?id=6076
    			score = mWoT.getScore(mWoT.getOwnIdentityByID(trusterID), mWoT.getIdentityByID(trusteeID));
     		} catch(NotInTrustTreeException e) {}
    		
    		handleGetScore(sfs, score, "0");
    	}

    	sfs.putOverwrite("Message", "Score");
		return sfs;
    }
    
    private SimpleFieldSet handleGetScore(final SimpleFieldSet sfs, final Score score, final String suffix) {
    	final String prefix = "Scores." + suffix + ".";
    	
    	if(score == null) {
    		sfs.putOverwrite(prefix + "Value", "Nonexistent");
    		return sfs;
    	}
    	
		sfs.putOverwrite(prefix + "Truster", score.getTruster().getID());
		sfs.putOverwrite(prefix + "Trustee", score.getTrustee().getID());
		sfs.putOverwrite(prefix + "Capacity", Integer.toString(score.getCapacity()));
		sfs.putOverwrite(prefix + "Rank", Integer.toString(score.getRank()));
		sfs.putOverwrite(prefix + "Value", Integer.toString(score.getScore()));
		sfs.putOverwrite(prefix + "VersionID", score.getVersionID().toString());
		
    	sfs.putOverwrite("Scores.Amount", "1");
    	
		return sfs;
    }



    private SimpleFieldSet handleSetTrust(final SimpleFieldSet params)
    	throws InvalidParameterException, NumberFormatException, UnknownIdentityException
    {
    	final String trusterID = getMandatoryParameter(params, "Truster");
    	final String trusteeID = getMandatoryParameter(params, "Trustee");
    	final String trustValue = getMandatoryParameter(params, "Value");
    	final String trustComment = getMandatoryParameter(params, "Comment");

    	mWoT.setTrust(trusterID, trusteeID, Byte.parseByte(trustValue), trustComment);

    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	sfs.putOverwrite("Message", "TrustSet");
		sfs.putOverwrite("Truster", trusterID);
		sfs.putOverwrite("Trustee", trusteeID);
		sfs.putOverwrite("Value", trustValue);
		sfs.putOverwrite("Comment", trustComment);
    	return sfs;
    }
    
    private SimpleFieldSet handleRemoveTrust(final SimpleFieldSet params)
		throws InvalidParameterException, NumberFormatException, UnknownIdentityException
	{
		final String trusterID = getMandatoryParameter(params, "Truster");
		final String trusteeID = getMandatoryParameter(params, "Trustee");

		mWoT.removeTrust(trusterID, trusteeID);
	
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "TrustRemoved");
		sfs.putOverwrite("Truster", trusterID);
		sfs.putOverwrite("Trustee", trusteeID);
		return sfs;
	}

    /**
     * NOTICE: The added identity will not be fetched unless you also add a positive {@link Trust} value from an {@link OwnIdentity} to it.
     * (An exception would be if another identity which is being fetched starts trusting the added identity at some point in the future)
     */
    private SimpleFieldSet handleAddIdentity(final SimpleFieldSet params) throws InvalidParameterException, MalformedURLException {
    	final String requestURI = getMandatoryParameter(params, "RequestURI");

        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "IdentityAdded");

        // TODO: Performance: The synchronized() can be removed after this is fixed:
        // https://bugs.freenetproject.org/view.php?id=6247
        synchronized(mWoT) {
            final Identity identity = mWoT.addIdentity(requestURI);
            sfs.putOverwrite("ID", identity.getID());
            sfs.putOverwrite("Nickname", identity.getNickname());
        }
    	return sfs;
    }
    
    /**
     * Used for handling the "GetIdentity" FCP message.
     */
    private SimpleFieldSet handleGetIdentity(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String trusterID = params.get("Truster");
    	final String identityID = getMandatoryParameter(params, "Identity");

    	final SimpleFieldSet sfs;
    	
        // We query two Identity objects from the database. Thus we must synchronize to ensure that
        // the returned data is coherent - one of the two might be deleted meanwhile, or change
        // from being an Identity to being an OwnIdentity.
    	synchronized(mWoT) {
    		final Identity identity = mWoT.getIdentityByID(identityID);
    		final OwnIdentity truster = (trusterID != null ? mWoT.getOwnIdentityByID(trusterID) : null);
    		
    		sfs = handleGetIdentity(identity, truster);
    		sfs.putOverwrite("Message", "Identity");
    	}
    	
		return sfs;
	}
    
    /**
     * Used as backend for:
     * - {@link #handleGetIdentity(SimpleFieldSet)}
     * - {@link #sendIdentityChangedNotification(String, IdentityChangedNotification)}
     */
    private SimpleFieldSet handleGetIdentity(final Identity identity, final OwnIdentity truster) {
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    		
           	// TODO: As of 2013-10-24, this is deprecated code to support old FCP clients.
    	    // Remove it after some time. Also do not forget to remove the appropriate
    	    // Stuff.DeprecatedField=true and Stuff.DeprecatedField=false in the rest of this
    	    // function then.
    		addIdentityFields(sfs, identity,"", "0");

            // TODO: As of 2013-10-24, this is deprecated code to support old FCP clients.
            // Remove it after some time. Also do not forget to remove the appropriate
            // Stuff.DeprecatedField=true and Stuff.DeprecatedField=false in the rest of this
            // function then.
            addIdentityFields(sfs, identity,"", "");
            
            // The above two have both an empty prefix, and all non-deprecated stuff which this
            // function adds has a well-defined prefix, so we can use "*.DeprecatedField" to mark
            // the above two as deprecated by whitelisting the non-deprecated stuff with
            // "WellDefinedPrefix.DeprecatedField=false"
            if(logMINOR)
                sfs.put("*.DeprecatedField", true);
            
            addIdentityFields(sfs, identity, "Identities.0.", "");
            // Don't include the "0": The addIdentityFields will add a field Identities.Amount
            if(logMINOR)
                sfs.put("Identities.*.DeprecatedField", false);
            
    		if(truster != null) {
    			Trust trust = null;
    			Score score = null;
    			
    			try {
    				trust = mWoT.getTrust(truster, identity);
    			} catch(NotTrustedException e) {}
    			
    			try {
    				score = mWoT.getScore(truster, identity);
    			} catch(NotInTrustTreeException e) {}
    			
    			handleGetTrust(sfs, trust, "0");
    			if(logMINOR)
    			    sfs.put("Trusts.*.DeprecatedField", false);
    			
    			handleGetScore(sfs, score, "0");
    			if(logMINOR)
    			    sfs.put("Scores.*.DeprecatedField", false);
    			
    			// No "DeprecatedField" entries needed for the following four, they all add them
    			// on their own already.
    			
            	addTrustFields(sfs, trust, "0"); // TODO: As of 2013-10-25, this is deprecated code to support old FCP clients. Remove it after some time.
            	addScoreFields(sfs, score, "0"); // TODO: As of 2013-10-25, this is deprecated code to support old FCP clients. Remove it after some time.
            
            	addTrustFields(sfs, trust, "");	// TODO: As of 2013-08-02, this is deprecated code to support old FCP clients. Remove it after some time.
            	addScoreFields(sfs, score, ""); // TODO: As of 2013-08-02, this is deprecated code to support old FCP clients. Remove it after some time.
    		}
    	
		return sfs;
	}


    /**
     * Add fields describing the given identity:
     * 
     * All following field names are prefixed with the given prefix and suffixed with the given suffix:
     * 
     * Type = type of the identity,  "Nonexistent", "OwnIdentity" or "Identity".
     * If the Type is  "Nonexistent", the identity does not exist anymore and no other fields will
     * be present.
     * 
     * Nickname = nickname of the identity
     * RequestURI = request URI of the identity
     * InsertURI = insert URI of the identity. Only present if Type is OwnIdentity
     * Identity = ID of the identity (deprecated)
     * ID = ID of the identity
     * PublishesTrustList = true/false if the identity does publish a trust list or not
     * CurrentEditionFetchState = See {@link Identity#getCurrentEditionFetchState()}
     * 
     * All following field names are NOT prefixed/suffixed unless "PREFIX"/"SUFFIX" is explicitely contained:
     * 
     * If suffix.isEmpty() is true (those are deprecated, do not use them in new parsers):
     * PREFIXContextX = name of context with index X
     * PREFIXPropertyX.Name = name of property with index X
     * PREFIXPropertyX.Value = value of property with index X
     * 
     * If suffix.isEmpty() is false (those are deprecated, do not use them in new parsers):
     * PREFIXContextsSUFFIX.ContextX = name of context with index X
     * PREFIXPropertiesSUFFIX.PropertyX.Name = name of property X
     * PREFIXPropertiesSUFFIX.PropertyX.Value = value of property X
     * 
     * Always:
     * Identities.Amount = "1". Overwrite this if you add more identities to the SFS.
     * 
     * PREFIXContextsSUFFIX.Amount = number of contexts
     * PREFIXPropertiesSUFFIX.Amount = number of properties
     * 
     * PREFIXContexts.X.Name = name of context with index X
     * PREFIXProperties.X.Name = name of property X
     * PREFIXProperties.X.Value = value of property X
     * 
     * @param sfs The {@link SimpleFieldSet} to add fields to.
     * @param identity The {@link Identity} to describe. Can be null to signal that the identity does not exist anymore.
     * @param prefix Added as descriptor for possibly multiple identities. Empty string is special case as explained in the function description.
     * @param suffix Added as descriptor for possibly multiple identities. Empty string is special case as explained in the function description.
     */
    private void addIdentityFields(SimpleFieldSet sfs, Identity identity, final String prefix, String suffix) {
    	if(identity == null) {
    		sfs.putOverwrite(prefix + "Type" + suffix, "Nonexistent");
    		return;
    	}
    	
    	sfs.putOverwrite(prefix + "Type" + suffix, (identity instanceof OwnIdentity) ? "OwnIdentity" : "Identity");
        sfs.putOverwrite(prefix + "Nickname" + suffix, identity.getNickname());
        sfs.putOverwrite(prefix + "RequestURI" + suffix, identity.getRequestURI().toString());
        
        sfs.putOverwrite(prefix + "Identity" + suffix, identity.getID()); // TODO: As of 2013-09-11, this is legacy code to support old FCP clients. Remove it after some time.
        if(logMINOR)
            sfs.put(prefix + "Identity" + suffix + ".DeprecatedField", true);
        
 		sfs.putOverwrite(prefix + "ID" + suffix, identity.getID());
 		sfs.putOverwrite(prefix + "VersionID" + suffix, identity.getVersionID().toString());
 		
        sfs.put(prefix + "PublishesTrustList" + suffix, identity.doesPublishTrustList());

 		if(identity instanceof OwnIdentity) {
 			OwnIdentity ownId = (OwnIdentity)identity;
 			sfs.putOverwrite(prefix + "InsertURI" + suffix, ownId.getInsertURI().toString());
 		}
        sfs.putOverwrite(prefix + "CurrentEditionFetchState" + suffix, identity.getCurrentEditionFetchState().toString());
        
 		final ArrayList<String> contexts = identity.getContexts();
 		final HashMap<String, String> properties = identity.getProperties();
 		
        if (suffix.isEmpty()) {	 // Deprecated
     		int contextCounter = 0;
     		int propertyCounter = 0;
     		
            for(String context : contexts) {
                sfs.putOverwrite(prefix + "Context" + contextCounter++, context);
            }
            if(logMINOR)
                sfs.put(prefix + "Context*.DeprecatedField", true);
            
            for (Entry<String, String> property : properties.entrySet()) {
                sfs.putOverwrite(prefix + "Property" + propertyCounter + ".Name", property.getKey());
                sfs.putOverwrite(prefix + "Property" + propertyCounter++ + ".Value", property.getValue());
            }
            if(logMINOR)
                sfs.put(prefix + "Property*.*.DeprecatedField", true);
        } else { // Deprecated
     		int contextCounter = 0;
     		int propertyCounter = 0;
     		
        	for(String context : contexts) {
                sfs.putOverwrite(prefix + "Contexts" + suffix + ".Context" + contextCounter++, context);
            }
        	
        	if(logMINOR)
        	    sfs.put(prefix + "Contexts" + suffix + ".Context*.DeprecatedField", true);
            
            for (Entry<String, String> property : properties.entrySet()) {
                sfs.putOverwrite(prefix + "Properties" + suffix + ".Property" + propertyCounter + ".Name", property.getKey());
                sfs.putOverwrite(prefix + "Properties" + suffix + ".Property" + propertyCounter++ + ".Value", property.getValue());
            }
            
            if(logMINOR)
                sfs.put(prefix + "Properties" + suffix + ".Property*.*.DeprecatedField", true);
        }
        
 		int contextCounter = 0;
 		int propertyCounter = 0;
        
    	for(String context : contexts) { // Non-deprecated
            sfs.putOverwrite(prefix + "Contexts." + contextCounter++ + ".Name", context);
        }
        
        for (Entry<String, String> property : properties.entrySet()) { // Non-deprecated
            sfs.putOverwrite(prefix + "Properties." + propertyCounter + ".Name", property.getKey());
            sfs.putOverwrite(prefix + "Properties." + propertyCounter++ + ".Value", property.getValue());
        }
        
        sfs.put(prefix + "Contexts" + suffix + ".Amount", contextCounter);
        sfs.put(prefix + "Properties" + suffix + ".Amount", propertyCounter);
        
        sfs.putOverwrite("Identities.Amount", "1");
    }
    
    /**
     * Adds fields (currently only one) describing the trust value from the given truster to the given trustee:
     * 
     * TrustSUFFIX = Value of trust, from -100 to +100. "null" if no such trust exists.
     * 
     * @param suffix Added as descriptor for possibly multiple identities.
     * @deprecated Use handleGetTrust instead.
     */
    @Deprecated
    private void addTrustFields(SimpleFieldSet sfs, final Trust trust, String suffix) {
        if(trust != null)
            sfs.putOverwrite("Trust" + suffix, Byte.toString(trust.getValue()));
        else
            sfs.putOverwrite("Trust" + suffix, "null");
        
        if(logMINOR)
            sfs.put("Trust" + suffix + ".DeprecatedField", true);
    }
    
    /**
     * Adds field describing the given score value
     * 
     * ScoreSUFFIX = Integer value of the Score. "null" if score is null.
     * RankSUFFIX = Integer value of the rank of the score. "null" if score is null.
     * 
     * @param suffix Added as descriptor for possibly multiple identities.
     * @deprecated Use handleGetScore() instead
     */
    @Deprecated
    private void addScoreFields(SimpleFieldSet sfs, Score score, String suffix) {
    	if(score != null) {
            sfs.putOverwrite("Score" + suffix, Integer.toString(score.getScore()));
            sfs.putOverwrite("Rank" + suffix, Integer.toString(score.getRank()));
    	} else {
            sfs.putOverwrite("Score" + suffix, "null");
            sfs.putOverwrite("Rank" + suffix, "null");
    	}
    	
    	if(logMINOR) {
    	    sfs.put("Score" + suffix + ".DeprecatedField", true);
    	    sfs.put("Rank" + suffix + ".DeprecatedField", true);
    	}
    }

    private SimpleFieldSet handleGetOwnIdentities(final SimpleFieldSet params) {
        final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "OwnIdentities");

        // getAllOwnIdentities() demands that we synchronize while processing the returned list.
		synchronized(mWoT) {
			int i = 0;
			for(final OwnIdentity oid : mWoT.getAllOwnIdentities()) {
			    // TODO: Unify the layout of this message to conform to the standard which is being
			    // used in most other messages: It should be Identity.X.Nickname=... instead of
			    // NicknameX=..., etc.
			    // See addIdentityFields() for example.
			    
				sfs.putOverwrite("Identity" + i, oid.getID()); // TODO: This should be "ID"
				sfs.putOverwrite("RequestURI" + i, oid.getRequestURI().toString());
				sfs.putOverwrite("InsertURI" + i, oid.getInsertURI().toString());
				sfs.putOverwrite("Nickname" + i, oid.getNickname());
				// TODO: Allow the client to select what data he wants

				int contextCounter = 0;
				for (String context : oid.getContexts()) {
				    // TODO: Unify to be same as in addIdentityFields()
					sfs.putOverwrite("Contexts" + i + ".Context" + contextCounter++, context);
				}

				int propertiesCounter = 0;
				for (Entry<String, String> property : oid.getProperties().entrySet()) {
                    // TODO: Unify to be same as in addIdentityFields()
					sfs.putOverwrite("Properties" + i + ".Property" + propertiesCounter + ".Name", property.getKey());
					sfs.putOverwrite("Properties" + i + ".Property" + propertiesCounter++ + ".Value", property.getValue());
				}
				// This is here so you do not forget to do it IN the "if()" if you add an if() around the put() statements to allow selection
				++i;
			}
			
			sfs.put("Amount", i);
		}

		return sfs;
    }

    private FCPPluginMessage handleGetIdentities(final FCPPluginMessage request) {
        final FCPPluginMessage result = FCPPluginMessage.constructSuccessReply(request);
        
        result.params.putOverwrite("Message", "Identities");
		
        final String context = request.params.get("Context");
        
        // WebOfTrust.getAllIdentities() demands that we synchronize while processing the result.
		synchronized(mWoT) {
			final boolean getAll = context == null || context.equals("");
	
			int i = 0;
			for(final Identity identity : mWoT.getAllIdentities()) {
				if(getAll || identity.hasContext(context)) {
					// TODO: Allow the client to select what data he wants
                    addIdentityFields(result.params, identity,
                        "Identities." + Integer.toString(i) + ".", "");
					
					++i;
				}
			}
            
            // Need to use Overwrite because addIdentityFields() sets it to 1
            result.params.putOverwrite("Identities.Amount", Integer.toString(i));
		}
		
        return result;
    }

    private FCPPluginMessage handleGetTrusts(final FCPPluginMessage request) {
        final FCPPluginMessage result = FCPPluginMessage.constructSuccessReply(request);
        
        result.params.putOverwrite("Message", "Trusts");
   
        // WebOfTrust.getAllTrusts() demands that we synchronize while processing the result.
        synchronized(mWoT) {
        	int i = 0;
			for(final Trust trust : mWoT.getAllTrusts()) {
                handleGetTrust(result.params, trust, Integer.toString(i));
				++i;
			}
            
            // Need to use Overwrite because handleGetTrust() sets it to 1
            result.params.putOverwrite("Trusts.Amount", Integer.toString(i));
        }
        
        return result;
    }

    private FCPPluginMessage handleGetScores(final FCPPluginMessage request) {
        final FCPPluginMessage result = FCPPluginMessage.constructSuccessReply(request);
       
        result.params.putOverwrite("Message", "Scores");
   
        // WebOfTrust.getAllScores() demands that we synchronize while processing the result.
        synchronized(mWoT) {
        	int i = 0;
			for(final Score score: mWoT.getAllScores()) {
                handleGetScore(result.params, score, Integer.toString(i));
				
				++i;
			}
            
            // Need to use Overwrite because handleGetScore() sets it to 1
            result.params.putOverwrite("Scores.Amount", Integer.toString(i));
        }
        
        return result;
    }

    private SimpleFieldSet handleGetIdentitiesByScore(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException, FSParseException {
    	final String trusterID = params.get("Truster");
        final String selection = getMandatoryParameter(params, "Selection");
        final String context = getMandatoryParameter(params, "Context");
        final boolean includeTrustValue = params.getBoolean("WantTrustValues", false);

		final String selectString = selection.trim();
		int select = 0; // TODO: decide about the default value
		
		if (selectString.equals("+")) select = 1;
		else if (selectString.equals("-")) select = -1;
		else if (selectString.equals("0")) select = 0;
		else throw new InvalidParameterException("Unhandled selection value (" + select + ")");

		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Identities");
		
        // WebOfTrust.getIdentitiesByScore() demands that we synchronize while processing the result
        // Also, we query the OwnIdentity truster before calling it, i.e. query two datasets
        // from the database. Thus we must synchronize to ensure that the returned data is coherent
        // - the truster might be deleted meanwhile, or change from being an OwnIdentity to being an
        // Identity.
		synchronized(mWoT) {
			final OwnIdentity truster = trusterID != null ? mWoT.getOwnIdentityByID(trusterID) : null;
			final boolean getAll = context.equals("");
	
			int i = 0;
			for(final Score score : mWoT.getIdentitiesByScore(truster, select)) {
				if(getAll || score.getTrustee().hasContext(context)) {
					// TODO: Allow the client to select what data he wants
					final OwnIdentity scoreOwner = score.getTruster();
					final Identity identity = score.getTrustee();
					final String suffix = Integer.toString(i);
					
					// TODO: As of 2013-10-24, this is deprecated code to support old FCP clients.
					// Remove it after some time. Make sure to update all DeprecatedFields entries
					// which this function adds.
					addIdentityFields(sfs, identity, "", suffix);
					// The above has no prefix, so we set it as deprecated as a whole, and then
					// whitelist other stuff by setting DeprecatedField=false:
					if(logMINOR)
					    sfs.put("*.DeprecatedField", true);
					
					addIdentityFields(sfs, identity, "Identities." + suffix + ".", "");
					if(logMINOR)
					    sfs.put("Identities." + suffix + ".*.DeprecatedField", false);
					
					// Adds DeprecatedField entries on its own.
					addScoreFields(sfs, score, suffix); // TODO: As of 2013-10-25, this is deprecated code to support old FCP clients. Remove it after some time.
					
					handleGetScore(sfs, score, suffix);
					if(logMINOR)
					    sfs.put("Scores.*.DeprecatedField", false);
					
					if(includeTrustValue) {
			            Trust trust = null;
						try {
							trust = mWoT.getTrust(scoreOwner, identity);
						} catch(NotTrustedException e) {}
						
		                // Adds DeprecatedField entries on its own.
						addTrustFields(sfs, trust, suffix); // TODO: As of 2013-10-25, this is deprecated code to support old FCP clients. Remove it after some time.
						
						handleGetTrust(sfs, trust, suffix);
						if(logMINOR)
						    sfs.put("Trusts.*.DeprecatedField", false);
					}
					
					if(truster == null) { // TODO: As of 2013-10-25, this is deprecated code to support old FCP clients. Remove it after some time.
		    			sfs.putOverwrite("ScoreOwner" + i, scoreOwner.getID());
		    			if(logMINOR)
		    			    sfs.put("ScoreOwner" + i + ".DeprecatedField", true); 
					}
					
					++i;
				}
			}
			
			sfs.put("Amount", i);
			sfs.put("Identities.Amount", i);
		}
		
		return sfs;
    }

    /**
     * TODO: Unify message layout to be same as in {@link #handleGetIdentities(FCPPluginMessage)}
     */
    private SimpleFieldSet handleGetTrusters(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
    	final String context = getMandatoryParameter(params, "Context");
    	
        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "Identities");
        
        final boolean getAll = context.equals("");
        
        // WebOfTrust.getReceivedTrusts() demands that we synchronize while processing the result.
        // Also, we query the Identity trustee before calling it, i.e. query two datasets
        // from the database. Thus we must synchronize to ensure that the returned data is
        // coherent - the trustee might be deleted meanwhile.
        synchronized(mWoT) {
        	int i = 0;
			for(final Trust trust : mWoT.getReceivedTrusts(mWoT.getIdentityByID(identityID))) {
				if(getAll || trust.getTruster().hasContext(params.get("Context"))) {
					sfs.putOverwrite("Identity" + i, trust.getTruster().getID());
					sfs.putOverwrite("Nickname" + i, trust.getTruster().getNickname());
					sfs.putOverwrite("RequestURI" + i, trust.getTruster().getRequestURI().toString());
					sfs.putOverwrite("Value" + i, Byte.toString(trust.getValue()));
					sfs.putOverwrite("Comment" + i, trust.getComment());

					int contextCounter = 0;
					for (String identityContext: trust.getTruster().getContexts()) {
						sfs.putOverwrite("Contexts" + i + ".Context" + contextCounter++, identityContext);
					}

					int propertiesCounter = 0;
					for (Entry<String, String> property : trust.getTruster().getProperties().entrySet()) {
						sfs.putOverwrite("Properties" + i + ".Property" + propertiesCounter + ".Name", property.getKey());
						sfs.putOverwrite("Properties" + i + ".Property" + propertiesCounter++ + ".Value", property.getValue());
					}
					// TODO: Allow the client to select what data he wants
					++i;
				}
			}
			sfs.put("Amount", i);
        }
        
        return sfs;
    }
    
    private SimpleFieldSet handleGetTrustersCount(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
    	//final String context = getMandatoryParameter(params, "Context"); // TODO: Implement as soon as we have per-context trust

        String selection = params.get("Selection");
        final int result;
        
        if(selection != null) {
        	selection = selection.trim();
    		final int select;
    		
    		if (selection.equals("+")) select = 1;
    		else if (selection.equals("-")) select = -1;
    		else if (selection.equals("0")) select = 0;
    		else throw new InvalidParameterException("Unhandled selection value (" + selection + ")");
        	
            // getReceivedTrusts() demands that we synchronize while processing the result.
            // Also, we query the Identity truster before calling it, i.e. query two datasets
            // from the database. Thus we must synchronize to ensure that the returned data is
            // coherent - the truster might be deleted meanwhile.
    		synchronized(mWoT) {
        		result = mWoT.getReceivedTrusts(mWoT.getIdentityByID(identityID), select).size();
        	}
        } else {
            // getReceivedTrusts() demands that we synchronize while processing the result.
            // Also, we query the Identity truster before calling it, i.e. query two datasets
            // from the database. Thus we must synchronize to ensure that the returned data is
            // coherent - the truster might be deleted meanwhile.
        	synchronized(mWoT) {
        		result = mWoT.getReceivedTrusts(mWoT.getIdentityByID(identityID)).size();
        	}
        }
    	
        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "TrustersCount");
        sfs.put("Value", result);
        return sfs;
    }

    /**
     * TODO: Unify message layout to be same as in {@link #handleGetIdentities(FCPPluginMessage)}
     */
    private SimpleFieldSet handleGetTrustees(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
    	final String context = getMandatoryParameter(params, "Context");

    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "Identities");
        
        final boolean getAll = context.equals("");

        // WebOfTrust.getGivenTrusts() demands that we synchronize while processing the result.
        // Also, we query the Identity truster before calling it, i.e. query two datasets
        // from the database. Thus we must synchronize to ensure that the returned data is
        // coherent - the truster might be deleted meanwhile.
        synchronized(mWoT) {
        	int i = 0;
        	for(final Trust trust : mWoT.getGivenTrusts(mWoT.getIdentityByID(identityID))) {
        		final Identity trustee = trust.getTrustee();

				if(getAll || trustee.hasContext(params.get("Context"))) {
					sfs.putOverwrite("Identity" + i, trustee.getID());
					sfs.putOverwrite("Nickname" + i, trustee.getNickname());
					sfs.putOverwrite("RequestURI" + i, trustee.getRequestURI().toString());
					sfs.putOverwrite("Value" + i, Byte.toString(trust.getValue()));
					sfs.putOverwrite("Comment" + i, trust.getComment());

					int contextCounter = 0;
					for (String identityContext: trust.getTruster().getContexts()) {
						sfs.putOverwrite("Contexts" + i + ".Context" + contextCounter++, identityContext);
					}

					int propertiesCounter = 0;
					for (Entry<String, String> property : trust.getTruster().getProperties().entrySet()) {
						sfs.putOverwrite("Properties" + i + ".Property" + propertiesCounter + ".Name", property.getKey());
						sfs.putOverwrite("Properties" + i + ".Property" + propertiesCounter++ + ".Value", property.getValue());
					}
					// TODO: Allow the client to select what data he wants
					++i;
				}
        	}
        	sfs.put("Amount", i);
        }
        
        return sfs;
    }
    
    private SimpleFieldSet handleGetTrusteesCount(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
    	//final String context = getMandatoryParameter(params, "Context"); // TODO: Implement as soon as we have per-context trust

        String selection = params.get("Selection");
        final int result;
        
        if(selection != null) {
        	selection = selection.trim();
    		final int select;
    		
    		if (selection.equals("+")) select = 1;
    		else if (selection.equals("-")) select = -1;
    		else if (selection.equals("0")) select = 0;
    		else throw new InvalidParameterException("Unhandled selection value (" + selection + ")");
        	
            // WebOfTrust.getGivenTrusts() demands that we synchronize while processing the result.
            // Also, we query the Identity truster before calling it, i.e. query two datasets
            // from the database. Thus we must synchronize to ensure that the returned data is
            // coherent - the truster might be deleted meanwhile.
    		synchronized(mWoT) {
        		result = mWoT.getGivenTrusts(mWoT.getIdentityByID(identityID), select).size();
        	}
        } else {
            // WebOfTrust.getGivenTrusts() demands that we synchronize while processing the result.
            // Also, we query the Identity truster before calling it, i.e. query two datasets
            // from the database. Thus we must synchronize to ensure that the returned data is
            // coherent - the truster might be deleted meanwhile.
        	synchronized(mWoT) {
        		result = mWoT.getGivenTrusts(mWoT.getIdentityByID(identityID)).size();
        	}
        }
    	
        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "TrusteesCount");
        sfs.put("Value", result);
        return sfs;
    }
    
    private SimpleFieldSet handleAddContext(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
    	final String context = getMandatoryParameter(params, "Context");

        mWoT.addContext(identityID, context);

        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "ContextAdded");
        return sfs;
    }

    private SimpleFieldSet handleRemoveContext(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
    	final String context = getMandatoryParameter(params, "Context");

        mWoT.removeContext(identityID, context);

        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "ContextRemoved");
        return sfs;
    }

    private SimpleFieldSet handleSetProperty(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
        final String propertyName = getMandatoryParameter(params, "Property");
        final String propertyValue = getMandatoryParameter(params, "Value");

        mWoT.setProperty(identityID, propertyName, propertyValue);

        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "PropertyAdded");
        return sfs;
    }

    private SimpleFieldSet handleGetProperty(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
        final String propertyName = getMandatoryParameter(params, "Property");

        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "PropertyValue");
        sfs.putOverwrite("Property", mWoT.getProperty(identityID, propertyName));
        return sfs;
    }

    private SimpleFieldSet handleRandomName(final SimpleFieldSet params) {
        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "Name");
	    sfs.putOverwrite("Name", RandomName.newNickname());
	    return sfs;
    }

    private SimpleFieldSet handleRemoveProperty(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
        final String propertyName = getMandatoryParameter(params, "Property");

        mWoT.removeProperty(identityID, propertyName);

        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "PropertyRemoved");
        return sfs;
    }
    
    private SimpleFieldSet handleGetIntroductionPuzzles(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
    	final String type = getMandatoryParameter(params, "Type");
    	final int amount = Integer.valueOf(getMandatoryParameter(params, "Amount"));
    	
        // getPuzzles() will return clone()s only, so no synchronized() is needed.
    	List<IntroductionPuzzle> puzzles
    	    = mWoT.getIntroductionClient().getPuzzles(identityID, PuzzleType.valueOf(type), amount);
    	
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	sfs.putOverwrite("Message", "IntroductionPuzzles");
    	
    	int index = 0;
    	
    	for(IntroductionPuzzle puzzle : puzzles) {
    		sfs.putOverwrite("Puzzle" + index, puzzle.getID());
    		++index;
    	}
    	
    	sfs.put("Amount", index);
    	
    	return sfs;
    }
    
    private SimpleFieldSet handleGetIntroductionPuzzle(final SimpleFieldSet params) throws InvalidParameterException, UnknownPuzzleException {
    	final String puzzleID = getMandatoryParameter(params, "Puzzle");

        final SimpleFieldSet result = new SimpleFieldSet(true);
        result.putOverwrite("Message", "IntroductionPuzzle");

        final IntroductionPuzzleStore puzzleStore = mWoT.getIntroductionPuzzleStore();
        // TODO: Performance: The synchronized() can be removed after this is fixed:
        // https://bugs.freenetproject.org/view.php?id=6247
        synchronized(puzzleStore) {
            final IntroductionPuzzle puzzle = puzzleStore.getByID(puzzleID);
            result.putOverwrite("Type", puzzle.getType().toString());
            result.putOverwrite("MimeType", puzzle.getMimeType());
            result.putOverwrite("Data", Base64.encodeStandard(puzzle.getData()));
        }
    	
    	return result;
    }
    
    private SimpleFieldSet handleSolveIntroductionPuzzle(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException, UnknownPuzzleException {
    	final String identityID = getMandatoryParameter(params, "Identity");
    	final String puzzleID = getMandatoryParameter(params, "Puzzle");
    	final String solution = getMandatoryParameter(params, "Solution");
    	
    	mWoT.getIntroductionClient().solvePuzzle(identityID, puzzleID, solution);
    	
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	sfs.putOverwrite("Message", "PuzzleSolved");
    	return sfs;
    }
    
    /**
     * Processes the "Subscribe" FCP message, filing a {@link Subscription} to event-{@link Notification}s via {@link SubscriptionManager}.
     * <br><b>Required fields:</b><br>
     * "To" = "Identities" or "Trusts" or "Scores" - chooses among {@link IdentitiesSubscription} / {@link TrustsSubscription} /
     * {@link ScoresSubscription}.<br><br>
     * 
     * <b>Reply:</b><br>
     * The reply will have the same {@link FCPPluginMessage#identifier} as the
     * original "Subscribe" message which you first sent to subscribe, or in other words be the
     * reply to the original "Subscribe" message. It means that the subscription is active, and its
     * params will be formatted as: <br>
     * "Message" = "Subscribed"<br>
     * "SubscriptionID" = Random {@link UUID} of the Subscription.<br>
     * "To" = Same as the "To" field of your original message.<br><br>
     *     
     * <b>Errors</b>:<br>
     * If you are already subscribed to the selected type, you will only receive a single message:
     * <br>
     * {@link FCPPluginMessage#identifier} = same as of your "Subscribe" message<br>
     * {@link FCPPluginMessage#success} = false<br>
     * {@link FCPPluginMessage#errorCode} = "SubscriptionExistsAlready"<br>
     * {@link FCPPluginMessage#params}:<br>
     * "Message" = "Error"<br>
     * "SubscriptionID" = Same as in the original "Subscribed" message<br>
     * "To" = Same as you requested<br>
     * "OriginalMessage" = "Subscribe"<br><br>
     * 
     * <h1>Event {@link Notification}s</h1>
     * 
     * <h2>{@link BeginSynchronizationNotification} and {@link EndSynchronizationNotification}:</h2>
     * After the "Subscribed" message, a message of type "BeginSynchronizationEvent" will follow,
     * followed by a series of "ObjectChangedEvent" messages (see below), followed by a message of
     *  type "EndSynchronizationEvent" message. See {@link BeginSynchronizationNotification} and
     * {@link EndSynchronizationNotification} for an explanation of their purpose.<br>
     * 
     * <h2>{@link ObjectChangedNotification}s:</h2>
     * Further "ObjectChangedEvent" messages will be sent at any time in the future if
     * an {@link Identity} / {@link Trust} / {@link Score} object has changed.
     * They will contain the version of the object before the change and after the change.
     * For the format, see:
     * {@link #sendIdentityChangedNotification(String, IdentityChangedNotification)} /
     * {@link #sendTrustChangedNotification(String, TrustChangedNotification)} /
     * {@link #sendScoreChangedNotification(String, ScoreChangedNotification)}.
     * <br>
     * 
     * <h2>Replying to notifications:</h2>
     * By replying with a {@link FCPPluginMessage} with {@link FCPPluginMessage#success}=false, you
     * can signal that you want to receive the same notification again.
     * After a typical delay of {@link SubscriptionManager#PROCESS_NOTIFICATIONS_DELAY}, it will be re-sent.
     * There is a maximal amount of {@link SubscriptionManager#DISCONNECT_CLIENT_AFTER_FAILURE_COUNT} failures per FCP-Client.
     * If you exceed this limit, your subscriptions will be terminated. You will receive an "Unsubscribed" message then as long as
     * your client has not terminated the FCP connection. See
     * {@link #handleUnsubscribe(FCPPluginMessage)}.
     * The fact that you can request a notification to be re-sent may also be used to program your client in a transactional style:
     * If the transaction which processes an event-notification fails, you can indicate failure to the synchronous FCP sender and
     * WOT will then re-send the notification, causing the transaction to be retried.
     * 
     * If your client is shutting down or not interested in the subscription anymore, you should send an "Unsubscribe" message.
     * See {@link #handleUnsubscribe(FCPPluginMessage)}. This will make sure that WOT stops
     * gathering data for your subscription,
     * which would be expensive to do if its not even needed. But if you cannot send the message anymore due to a dropped connection,
     * the subscription will be terminated automatically after some time due to notification-deployment failing. Nevertheless,
     * please always unsubscribe when possible.<br><br>
     * 
     * TODO: Code quality: Review & improve this JavaDoc.
     * 
     * @see SubscriptionManager#subscribeToIdentities(String) The underlying implementation for "To" = "Identities"
     * @see SubscriptionManager#subscribeToScores(String) The underyling implementation for "To" = "Trusts"
     * @see SubscriptionManager#subscribeToTrusts(String) The underlying implementation for "To" = "Scores"
     */
    private FCPPluginMessage handleSubscribe(final FCPPluginConnection connection,
            final FCPPluginMessage message) throws InvalidParameterException {
        
        final String to = getMandatoryParameter(message.params, "To");

    	
    	try {
            FCPPluginMessage reply = FCPPluginMessage.constructSuccessReply(message);
            String subscriptionID;
            
            // TODO: Code quality: Use FCPClientReferenceImplementation.SubscriptionType.valueOf()
            // Maybe copy the enum to class SubscriptionManager. (It must be copied instead of moved
            // from FCPClientReferenceImplementation because that class should not require classes
            // which wouldn't make sense to copy to a WOT client plugin. SubscriptionManager for
            // sure does not need to be in a WOT client plugin)
	    	if(to.equals("Identities")) {
                subscriptionID = mSubscriptionManager.subscribeToIdentities(connection.getID());
	    	} else if(to.equals("Trusts")) {
                subscriptionID = mSubscriptionManager.subscribeToTrusts(connection.getID());
	    	} else if(to.equals("Scores")) {
                subscriptionID = mSubscriptionManager.subscribeToScores(connection.getID());
	    	} else
	    		throw new InvalidParameterException("Invalid subscription type specified: " + to);
	    	
	    	SimpleFieldSet sfs = reply.params;
	    	sfs.putOverwrite("Message", "Subscribed");
            sfs.putOverwrite("SubscriptionID", subscriptionID);
	    	sfs.putOverwrite("To", to);
            
            return reply;
    	} catch(SubscriptionExistsAlreadyException e) {
            FCPPluginMessage errorMessage = 
                errorMessageFCP(message, "SubscriptionExistsAlready",
                    null /* No error message since this API likely will not be used by UI */);
            errorMessage.params.putOverwrite("SubscriptionID", e.existingSubscription.getID());
            errorMessage.params.putOverwrite("To", to);
            return errorMessage;
    	} catch (InterruptedException e) {
    	    // Shutdown of WOT was requested. We must NOT send a message here:
    	    // - Returning a success message would be a lie. It would be very bad to leave the
    	    //   client with the false assumption that he is properly connected to WOT because
    	    //   that could be even displayed to the user, and as a result cause him to be
    	    //   disappointed because the UI won't show any WOT data since there is none but also
    	    //   not display any error message about not being connected to WOT.
    	    // Indicating that subscribing failed here would also be a bad idea because if we did,
    	    // this could happen:
    	    // - The client tries to re-subscribe because clients will rely heavily upon
    	    //   subscriptions.
    	    // - The client was implemented poorly though and has no delay before retrying, the 
    	    //   retry happens immediately.
    	    // - Because InterruptedException is only sent once to each thread, it doesn't happen
    	    //   on the retry, so the retry gets through (to executing this function here again)
    	    //   and causes the Subscription to be filed. 
    	    // - Creation of a Subscription is a very heavy operation because the synchronization
    	    //   of a Subscription requires a snapshot of the whole WOT database to be made
    	    //   (see the JavaDoc of this function).
    	    // - Thus, the retry takes a long time during which shutdown is blocked.
    	    // Thus, we exit silently without a reply here to ensure that the client's code which
    	    // waits for a success/failure message has to time out before it can retry.
    	    return null;
        }
    }
    
    /**
     * Handles the "Unsubscribe" message, the inverse operation to the "Subscribe".<br>
     * See {@link #handleSubscribe(FCPPluginConnection, FCPPluginMessage)}.
     * <b>Required fields:</b>
     * "SubscriptionID" = Must be equal to the value of the same field which you received in reply to the "Subscribe" message.
     * 
     * <b>Reply:</b>
     * "Message" = "Unsubscribed"
     * "From" = "Identities" or "Trusts" or "Scores" - indicates the type of the original subscription.
     * "SubscriptionID" = Same as requested
     */
    private FCPPluginMessage handleUnsubscribe(final FCPPluginMessage request)
            throws InvalidParameterException, UnknownSubscriptionException {
        
        final String subscriptionID = getMandatoryParameter(request.params, "SubscriptionID");
    	final Class<Subscription<? extends EventSource>> clazz
    	    = mSubscriptionManager.unsubscribe(subscriptionID);
        return handleUnsubscribe(request, clazz, subscriptionID);
    }
    
    public void sendUnsubscribedMessage(final UUID clientID,
            final Class<Subscription<? extends EventSource>> clazz, final String subscriptionID)
                throws IOException {
        
        mPluginRespirator.getPluginConnectionByID(clientID).send(
            handleUnsubscribe(null, clazz, subscriptionID));
    }
    
    /**
     * @param request
     *            Is only used for constructing the reply {@link FCPPluginMessage} as a reply to the
     *            given request. The parameters of the request are not parsed, you must parse
     *            them yourself and specify them via the other parameters.
     *            Can be null if you use this to terminate the subscription due to an event, not
     *            due to an original client message.
     */
    private FCPPluginMessage handleUnsubscribe(final FCPPluginMessage request,
        final Class<Subscription<? extends EventSource>> clazz, final String subscriptionID) {
        
        final FCPPluginMessage result =
            request != null ? FCPPluginMessage.constructSuccessReply(request) :
                              FCPPluginMessage.construct();
        
    	final String type;

        // TODO: Code quality: Use FCPClientReferenceImplementation.SubscriptionType.*.name()
    	// Also require a SubscriptionType as parameter instead of a Class.
    	// Maybe copy the enum to class SubscriptionManager. (It must be copied instead of moved
    	// from FCPClientReferenceImplementation because that class should not require classes
    	// which wouldn't make sense to copy to a WOT client plugin. SubscriptionManager for
    	// sure does not need to be in a WOT client plugin)
    	if(clazz.equals(IdentitiesSubscription.class))
    		type = "Identities";
    	else if(clazz.equals(TrustsSubscription.class))
    		type = "Trusts";
    	else if(clazz.equals(ScoresSubscription.class))
    		type = "Scores";
    	else
    		throw new IllegalStateException("Unknown subscription type: " + clazz);

        result.params.putOverwrite("Message", "Unsubscribed");
        result.params.putOverwrite("SubscriptionID", subscriptionID);
        result.params.putOverwrite("From", type);
        
        return result;
    }

    /**
     * ATTENTION: At shutdown of WOT, you have to make sure to use {@link Thread#interrupt()} to
     * interrupt any of your threads which call this function:<br>
     * It uses the blocking {@link FCPPluginConnection#sendSynchronous(FCPPluginMessage, long)},
     * which can take a long time to complete. It can be aborted by interrupt().<br><br>
     * 
     * {@link EndSynchronizationNotification} is a subclass of
     * {@link BeginSynchronizationNotification}, so this function can deal with both.
     */
    public void sendBeginOrEndSynchronizationNotification(final UUID clientID,
            final BeginSynchronizationNotification<?> notification)
                throws FCPCallFailedException, IOException, InterruptedException {
        
        // Not a reply to an existing message since it is sent due to an event, not a client message
        final FCPPluginMessage fcpMessage = FCPPluginMessage.construct();
        
        fcpMessage.params.putOverwrite("Message", 
             notification instanceof EndSynchronizationNotification 
                 ? "EndSynchronizationEvent" : "BeginSynchronizationEvent");
        
        Subscription<? extends EventSource> subscription = notification.getSubscription();
        String to;
        
        // The type parameter of the BeginSynchronizationNotification<T> is not known at runtime
        // due to the way Java is implemented. Thus, we must use the hack of checking the
        // class of the Subscription to which the Notification belongs:
        // Subscription is not parameterized, so we can check its class.
        // TODO: Code quality: Use FCPClientReferenceImplementation.SubscriptionType.*.name()
        // Maybe copy the enum to class SubscriptionManager. (It must be copied instead of moved
        // from FCPClientReferenceImplementation because that class should not require classes
        // which wouldn't make sense to copy to a WOT client plugin. SubscriptionManager for
        // sure does not need to be in a WOT client plugin)
        if(subscription instanceof IdentitiesSubscription)
            to = "Identities";
        else if (subscription instanceof TrustsSubscription)
            to = "Trusts";
        else if (subscription instanceof ScoresSubscription)
            to = "Scores";
        else  {
            throw new UnsupportedOperationException(
                "BeginSynchronizationNotification for unknown Subscription type: " + subscription);
        }
        
        // "To" because thats what we also use in handleSubscribe()
        fcpMessage.params.putOverwrite("To", to);
        fcpMessage.params.putOverwrite("VersionID", notification.getID());
        
        final FCPPluginMessage reply = mPluginRespirator.getPluginConnectionByID(clientID)
            .sendSynchronous(
                fcpMessage, TimeUnit.MINUTES.toNanos(SUBSCRIPTION_NOTIFICATION_TIMEOUT_MINUTES));
        
        if(reply.success == false)
            throw new FCPCallFailedException(reply);
    }

    /**
     * ATTENTION: At shutdown of WOT, you have to make sure to use {@link Thread#interrupt()} to
     * interrupt any of your threads which call this function:<br>
     * It uses the blocking {@link FCPPluginConnection#sendSynchronous(FCPPluginMessage, long)},
     * which can take a long time to complete. It can be aborted by interrupt().<br><br>
     * 
     * @see SubscriptionManager.IdentityChangedNotification
     */
    public void sendIdentityChangedNotification(final UUID clientID,
            final IdentityChangedNotification notification)
                throws FCPCallFailedException, IOException, InterruptedException {
        
    	final SimpleFieldSet oldIdentity = handleGetIdentity((Identity)notification.getOldObject(), null);
    	final SimpleFieldSet newIdentity = handleGetIdentity((Identity)notification.getNewObject(), null);
    	
        sendChangeNotification(clientID, SubscriptionType.Identities, oldIdentity, newIdentity);
    }
    
    /**
     * ATTENTION: At shutdown of WOT, you have to make sure to use {@link Thread#interrupt()} to
     * interrupt any of your threads which call this function:<br>
     * It uses the blocking {@link FCPPluginConnection#sendSynchronous(FCPPluginMessage, long)},
     * which can take a long time to complete. It can be aborted by interrupt().<br><br>
     * 
     * @see SubscriptionManager.TrustChangedNotification
     */
    public void sendTrustChangedNotification(final UUID clientID,
            final TrustChangedNotification notification)
                throws FCPCallFailedException, IOException, InterruptedException {
        
    	final SimpleFieldSet oldTrust = handleGetTrust(new SimpleFieldSet(true), (Trust)notification.getOldObject(), "0");
    	final SimpleFieldSet newTrust = handleGetTrust(new SimpleFieldSet(true), (Trust)notification.getNewObject(), "0");

        sendChangeNotification(clientID, SubscriptionType.Trusts, oldTrust, newTrust);
    }
    
    /**
     * ATTENTION: At shutdown of WOT, you have to make sure to use {@link Thread#interrupt()} to
     * interrupt any of your threads which call this function:<br>
     * It uses the blocking {@link FCPPluginConnection#sendSynchronous(FCPPluginMessage, long)},
     * which can take a long time to complete. It can be aborted by interrupt().<br><br>
     * 
     * @see SubscriptionManager.ScoreChangedNotification
     */
    public void sendScoreChangedNotification(final UUID clientID,
            final ScoreChangedNotification notification)
                throws FCPCallFailedException, IOException, InterruptedException {
        
    	final SimpleFieldSet oldScore = handleGetScore(new SimpleFieldSet(true), (Score)notification.getOldObject(), "0");
    	final SimpleFieldSet newScore = handleGetScore(new SimpleFieldSet(true), (Score)notification.getNewObject(), "0");

        sendChangeNotification(clientID, SubscriptionType.Scores, oldScore, newScore);
    }
    
    /**
     * ATTENTION: At shutdown of WOT, you have to make sure to use {@link Thread#interrupt()} to
     * interrupt any of your threads which call this function:<br>
     * It uses the blocking {@link FCPPluginConnection#sendSynchronous(FCPPluginMessage, long)},
     * which can take a long time to complete. It can be aborted by interrupt().<br><br>
     */
    private void sendChangeNotification(
            final UUID clientID, final SubscriptionType subscriptionType,
            final SimpleFieldSet beforeChange, final SimpleFieldSet afterChange)
                throws FCPCallFailedException, IOException, InterruptedException {
        
        // Not a reply to an existing message since it is sent due to an event, not a client message
        final FCPPluginMessage fcpMessage = FCPPluginMessage.construct();
        
        fcpMessage.params.putOverwrite("Message", "ObjectChangedEvent");
        fcpMessage.params.putOverwrite("SubscriptionType", subscriptionType.name());
        fcpMessage.params.put("Before", beforeChange);
        fcpMessage.params.put("After", afterChange);
        
        final FCPPluginMessage reply = mPluginRespirator.getPluginConnectionByID(clientID)
            .sendSynchronous(
                fcpMessage, TimeUnit.MINUTES.toNanos(SUBSCRIPTION_NOTIFICATION_TIMEOUT_MINUTES));
        
        if(reply.success == false)
            throw new FCPCallFailedException(reply);
    }
    
    private SimpleFieldSet handlePing() {
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	sfs.putOverwrite("Message", "Pong");
    	return sfs;
    }

    /**
     * ATTENTION: This does cause the {@link FCPPluginMessage#errorCode} field to be "InternalError"
     * which complicates error handling at the client. Therefore, only use this for Exception types
     * which you do not know. If you know what a certain Exception type means, use
     * {@link #errorMessageFCP(freenet.pluginmanager.FredPluginFCPMessageHandler.FCPPluginMessage,
     * String, String))} to set a proper errorCode (and errorMessage).<br>
     * Well-defined errorCode values should also be specified at the JavaDoc of the FCP message
     * handler which will return them upon error.
     */
    private FCPPluginMessage errorMessageFCP(final FCPPluginMessage originalMessage,
            final Exception e) {
        
        // "InternalError" and e.toString() are  suggested by the FCPPluginMessage JavaDoc.
        return errorMessageFCP(originalMessage, "InternalError", e.toString());
    }

    /**
     * TODO: Optimization: Remove the deprecated fields after some time. They were added 2014-09-23
     */
    private FCPPluginMessage errorMessageFCP(final FCPPluginMessage originalFCPMessage,
           final String errorCode, final String errorMessage) {
        
        FCPPluginMessage reply = FCPPluginMessage.constructErrorReply(
            originalFCPMessage, errorCode, errorMessage);
        
        final SimpleFieldSet sfs = reply.params;
        
        sfs.putOverwrite("OriginalMessage", originalFCPMessage.params.get("Message"));
        
        sfs.putOverwrite("Message", "Error");
        // NOT deprecated even though there is FCPPluginMessage.success already to indicate that a
        // message is an error message:
        // All other WOT FCP messages contain a "Message" field, which makes it likely that client
        // implementations are centered around switching on that field. See our own
        // FCPPluginClientReferenceImplementation for example.
        // It would complicate their code to have the exception of error messages not containing
        // the "Message" field.
        /* if(logMINOR) sfs.put("Message.DeprecatedField", true); */
        
        sfs.putOverwrite("Description", errorMessage);
        // Deprecated because there is FCPPluginMessage.errorMessage now
        if(logMINOR)
            sfs.put("Description.DeprecatedField", true);
        
        return reply;
        
    }
    
    
    /**
     * Thrown if delivery of a message to the client succeeded but the client indicated that the
     * processing of the message did not succeed (via {@link FCPPluginMessage#success} == false).
     * <br>In opposite to {@link IOException}, which should result in assuming the connection to the
     * client to be closed, this may be used to trigger re-sending of a certain message over the
     * same connection.
     */
    public static final class FCPCallFailedException extends Exception {
        private static final long serialVersionUID = 1L;
        
        public FCPCallFailedException(FCPPluginMessage clientReply) {
            super("The client indicated failure of processing the message."
                + " errorCode: " + clientReply.errorCode
                + "; errorMessage: " + clientReply.errorMessage);
            
            assert(clientReply.success == false);
        }
    }

}
