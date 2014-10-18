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

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.SubscriptionManager.IdentitiesSubscription;
import plugins.WebOfTrust.SubscriptionManager.IdentityChangedNotification;
import plugins.WebOfTrust.SubscriptionManager.Notification;
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
import plugins.WebOfTrust.util.RandomName;
import freenet.keys.FreenetURI;
import freenet.node.FSParseException;
import freenet.node.fcp.FCPPluginClient;
import freenet.node.fcp.FCPPluginClient.SendDirection;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.PluginReplySender;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * ATTENTION: There is a deprecation mechanism for getting rid of old SimpleFieldSet keys (fields)
 * in FCP messages sent by WOT:<br>
 * - If a {@link FCPPluginMessage} sent by WOT contains a value of "SomeField.DeprecatedField=true"
 *   in the {@link FCPPluginMessage#params}, then you should not write new client code to use the
 *   field "SomeField". A wildcard of "*" to match any characters can also be valid in the key name.
 *   <br>
 * - A value of "SomeField.DeprecatedField=false" can be used to exclude a field from the
 *   deprecation list if a wildcard "*" in "abc*abc.DeprecatedField=true matches more than desired.
 *   <br>
 * - If you want to change WOT to deprecate a certain field, use:<br>
 *   <code>aSimpleFieldSet.put("SomeField.DeprecatedField", true);</code><br>
 * - Notice that this is included in the actual on-network messages to ensure that client authors
 *   read and follow it. Also, it makes large messages which contain a lot of duplicate fields due
 *   to deprecation easier to understand. The data overhead is considered as acceptable because
 *   deprecated fields shall only exist temporarily anyway; and because FCP as a text mode
 *   protocol aims to be easy to read to humans, not space-efficient.<br>
 * FIXME: Review the whole class for fields which are deprecated but not listed at DeprecatedFields
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public final class FCPInterface implements FredPluginFCPMessageHandler.ServerSideFCPMessageHandler {

    /**
     * Timeout when sending a synchronization of a {@link SubscriptionManager.Subscription} to a
     * client. For an explanation of what a synchronization is, see
     * {@link #handleSubscribe(FCPPluginClient, FCPPluginMessage)}<br>
     * When this expires, deploying of the synchronization is considered as failed, and the
     * {@link SubscriptionManager} is notified about that. It then may re-sent it or terminate the
     * {@link Subscription} upon repeated failure. (The current implementation immediately
     * terminates the {@link Subscription}.<br><br>
     * 
     * FIXME: Optimization: To reduce this constant, the following can be done:<br>
     * The current implementation does not store the synchronization as a {@link Notification} but
     * rather sends it out immediately in
     * {@link #handleSubscribe(FCPPluginClient, FCPPluginMessage)}. This has the following bad
     * impact upon performance:<br>
     * - A synchronization possibly contains the whole database of Identity/Trust/Score, so it
     *   takes a long time to send it over the network.<br>
     * - The sending thread only returns *after* the client has processed it and sent a reply,
     *   which itself can take a long time since things such as Freetalk can have to do huge
     *   database restructuring such as deleting all messages of deleted Identitys.<br>
     * - The serialization of the Identity/Trust/Score objects into SimpleFieldSet happens in the
     *   sending thread as well, so it is added to the blocking time of the sending thread.<br>
     * - The lock upon the WOT is taken during the whole time it takes to send the message. So
     *   this will block everything else, including the web interface, during the sum of the time
     *   of the above three things.<br>
     * <br>
     * Instead, rewrite {@link SubscriptionManager} to store the synchronization as a
     * {@link Notification} object: Notification objects contain the event data as a copy of the
     * WOT objects serialized into a byte[], the event data is <b>not</b> read from the
     * {@link WebOfTrust} during the sending. They are send out later in a separate thread than the
     * one which read out the data from the {@link WebOfTrust}.<br> 
     * This will effectively allow us to split the aforementioned three bad effects (time to
     * serialize, time to send, time it takes for the client to process) into at last two stages:
     * <br>
     * - Generation of the {@link Notification}, which locks the {@link WebOfTrust}.<br>
     * - Sending of the {@link Notification}, which only locks the SubscriptionManager, not the
     *   {@link WebOfTrust}, and therefore not the user interface.<br> 
     * When fixing this make sure to adapt the JavaDoc at {@link #handleSubscribe(FCPPluginClient,
     * FCPPluginMessage)} afterwards.<br>
     * Notice that it will also have the bonus of allowing us to re-send a failed synchronization
     * instead of terminating the Subscription. If you implement this, be sure to change the JavaDoc
     * of this constant at the beginning to remove the part which says that a timeout results in
     * termination of the subscription.
     */
    public static final int SUBSCRIPTION_SYNCHRONIZATION_TIMEOUT_MINUTES = 5;

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

    
    public FCPInterface(final WebOfTrust myWoT) {
        mWoT = myWoT;
        mPluginRespirator = mWoT.getPluginRespirator();
        mSubscriptionManager = mWoT.getSubscriptionManager();
    }
    
    /** TODO: Could be removed, is empty. */
    public void start() {}
    
    public void stop() {
        // FIXME: Check whether we need to Thread.interrupt() eventually existing
        // FCPPluginClient.sendSynchronous() threads. If not, remove this commented out code,
        // this function, and probably also the callers.
        // Also, if we do need it, this probably should be placed in a different class than
        // ClientTrackerDaemon. I am keeping it here because the interrupt loop might be
        // useful for the sendSynchronous() stuff and I'm too lazy to save it elsewhere.
        
        /*
        enabled = false;
        do {
            for(all sendSynchronous threads) thread.interrupt();
            try {
                join(all sendSynchronous threads);
            } catch(InterruptedException e) {
                // FIXME: This is wrong, see https://bugs.freenetproject.org/view.php?id=6290
                Thread.interrupted();
            }
        } while(there are sendSynchronous threads);
        */
    }

    /** {@inheritDoc} */
    @Override
    public FCPPluginMessage handlePluginFCPMessage(
            FCPPluginClient client, FCPPluginMessage fcpMessage) {
        
        if(fcpMessage.isReplyMessage()) {
            Logger.warning(this, "Received an unexpected reply message: WOT currently should only"
                + " use FCPPluginClient.sendSynchronous() for anything which is replied to by the"
                + "client. Thus, all replies should be delivered to sendSynchronous() instead of the"
                + "asynchronous message handler. Maybe the"
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
                reply = handleSubscribe(client, fcpMessage);
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
    	
    	OwnIdentity identity;
    	
    	final String identityNickname = getMandatoryParameter(params, "Nickname");
    	final String identityContext = getMandatoryParameter(params, "Context");
    	final String identityPublishesTrustListStr = getMandatoryParameter(params, "PublishTrustList");
    	
    	final boolean identityPublishesTrustList = identityPublishesTrustListStr.equals("true") || identityPublishesTrustListStr.equals("yes");
    	final String identityInsertURI = params.get("InsertURI");

    	/* The constructor will throw for us if one is missing. Do not use "||" because that would lead to creation of a new URI if the
    	 * user forgot one of the URIs and the user would not get notified about that.  */
    	synchronized(mWoT) { /* Preserve the locking order to prevent future deadlocks */
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
    	}

    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "IdentityCreated");
        sfs.putOverwrite("ID", identity.getID());
        sfs.putOverwrite("InsertURI", identity.getInsertURI().toString());
        sfs.putOverwrite("RequestURI", identity.getRequestURI().toString());
        return sfs;
    }
    
    private SimpleFieldSet handleGetTrust(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String trusterID = getMandatoryParameter(params, "Truster");
    	final String trusteeID = getMandatoryParameter(params, "Trustee");
    	
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	synchronized(mWoT) {
    		Trust trust = null;
    		try {
        		// TODO: Optimize by implementing https://bugs.freenetproject.org/view.php?id=6076
    			trust = mWoT.getTrust(mWoT.getIdentityByID(trusterID), mWoT.getIdentityByID(trusteeID));
    		} catch(NotTrustedException e) {}
    		
    		handleGetTrust(sfs, trust, "0");
    	}
    	sfs.putOverwrite("Message", "Trust");
    	return sfs;
    }
    
    private SimpleFieldSet handleGetTrust(final SimpleFieldSet sfs, final Trust trust, String suffix) {
    	final String prefix = "Trusts." + suffix + ".";
    	
    	if(trust == null) {
    		sfs.putOverwrite(prefix + "Value", "Inexistent");
    		return sfs;
    	}
    	
		sfs.putOverwrite(prefix + "Truster", trust.getTruster().getID());
		sfs.putOverwrite(prefix + "Trustee", trust.getTrustee().getID());
		sfs.putOverwrite(prefix + "Value", Byte.toString(trust.getValue()));
		sfs.putOverwrite(prefix + "Comment", trust.getComment());
		sfs.put(prefix + "TrusterEdition", trust.getTrusterEdition());
		
    	sfs.putOverwrite("Trusts.Amount", "1");
    	
		return sfs;
    }
    
    private SimpleFieldSet handleGetScore(final SimpleFieldSet params) throws UnknownIdentityException, InvalidParameterException {
    	final String trusterID = getMandatoryParameter(params, "Truster");
    	final String trusteeID = getMandatoryParameter(params, "Trustee");

    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
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
    		sfs.putOverwrite(prefix + "Value", "Inexistent");
    		return sfs;
    	}
    	
		sfs.putOverwrite(prefix + "Truster", score.getTruster().getID());
		sfs.putOverwrite(prefix + "Trustee", score.getTrustee().getID());
		sfs.putOverwrite(prefix + "Capacity", Integer.toString(score.getCapacity()));
		sfs.putOverwrite(prefix + "Rank", Integer.toString(score.getRank()));
		sfs.putOverwrite(prefix + "Value", Integer.toString(score.getScore()));
		
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

    	final Identity identity = mWoT.addIdentity(requestURI);

    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	sfs.putOverwrite("Message", "IdentityAdded");
    	sfs.putOverwrite("ID", identity.getID());
    	sfs.putOverwrite("Nickname", identity.getNickname());
    	return sfs;
    }
    
    /**
     * Used for handling the "GetIdentity" FCP message.
     */
    private SimpleFieldSet handleGetIdentity(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String trusterID = params.get("Truster");
    	final String identityID = getMandatoryParameter(params, "Identity");

    	final SimpleFieldSet sfs;
    	
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
            sfs.put("*.DeprecatedField", true);
            
            addIdentityFields(sfs, identity, "Identities.0.", "");
            // Don't include the "0": The addIdentityFields will add a field Identities.Amount
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
    			sfs.put("Trusts.*.DeprecatedField", false);
    			
    			handleGetScore(sfs, score, "0");
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
     * Type = type of the identity,  "Inexistent", "OwnIdentity" or "Identity".
     * If the Type is  "Inexistent", the identity does not exist anymore and no other fields will be present.
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
    		sfs.putOverwrite(prefix + "Type" + suffix, "Inexistent");
    		return;
    	}
    	
    	sfs.putOverwrite(prefix + "Type" + suffix, (identity instanceof OwnIdentity) ? "OwnIdentity" : "Identity");
        sfs.putOverwrite(prefix + "Nickname" + suffix, identity.getNickname());
        sfs.putOverwrite(prefix + "RequestURI" + suffix, identity.getRequestURI().toString());
        sfs.putOverwrite(prefix + "Identity" + suffix, identity.getID()); // TODO: As of 2013-09-11, this is legacy code to support old FCP clients. Remove it after some time.
 		sfs.putOverwrite(prefix + "ID" + suffix, identity.getID());
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
            
            for (Entry<String, String> property : properties.entrySet()) {
                sfs.putOverwrite(prefix + "Property" + propertyCounter + ".Name", property.getKey());
                sfs.putOverwrite(prefix + "Property" + propertyCounter++ + ".Value", property.getValue());
            }
        } else { // Deprecated
     		int contextCounter = 0;
     		int propertyCounter = 0;
     		
        	for(String context : contexts) {
                sfs.putOverwrite(prefix + "Contexts" + suffix + ".Context" + contextCounter++, context);
            }
            
            for (Entry<String, String> property : properties.entrySet()) {
                sfs.putOverwrite(prefix + "Properties" + suffix + ".Property" + propertyCounter + ".Name", property.getKey());
                sfs.putOverwrite(prefix + "Properties" + suffix + ".Property" + propertyCounter++ + ".Value", property.getValue());
            }
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
    }

    private SimpleFieldSet handleGetOwnIdentities(final SimpleFieldSet params) {
        final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "OwnIdentities");

		synchronized(mWoT) {
			int i = 0;
			for(final OwnIdentity oid : mWoT.getAllOwnIdentities()) {
				sfs.putOverwrite("Identity" + i, oid.getID());
				sfs.putOverwrite("RequestURI" + i, oid.getRequestURI().toString());
				sfs.putOverwrite("InsertURI" + i, oid.getInsertURI().toString());
				sfs.putOverwrite("Nickname" + i, oid.getNickname());
				// TODO: Allow the client to select what data he wants

				int contextCounter = 0;
				for (String context : oid.getContexts()) {
					sfs.putOverwrite("Contexts" + i + ".Context" + contextCounter++, context);
				}

				int propertiesCounter = 0;
				for (Entry<String, String> property : oid.getProperties().entrySet()) {
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
    
    /**
     * @param request
     *            Can be null if you use this to send out identities due to an event, not due to
     *            an original client message.
     */
    private FCPPluginMessage handleGetIdentities(final FCPPluginMessage request) {
        final String context;
        
        if(request != null) {
        	context = request.params.get("Context");
        } else {
        	context = null;
        }

        final FCPPluginMessage result =
            request != null ? FCPPluginMessage.constructSuccessReply(request) :
                              FCPPluginMessage.construct();
        
        result.params.putOverwrite("Message", "Identities");
		
		// TODO: Optimization: Remove this lock if it works without it.
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

    /**
     * @param request
     *            Can be null if you use this to send out Trusts due to an event, not due to
     *            an original client message.
     */
    private FCPPluginMessage handleGetTrusts(final FCPPluginMessage request) {
        final FCPPluginMessage result =
            request != null ? FCPPluginMessage.constructSuccessReply(request) :
                              FCPPluginMessage.construct();
        
        result.params.putOverwrite("Message", "Trusts");
   
		// TODO: Optimization: Remove this lock if it works without it.
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
    
    /**
     * @param request
     *            Can be null if you use this to send out Trusts due to an event, not due to
     *            an original client message.
     */
    private FCPPluginMessage handleGetScores(final FCPPluginMessage request) {
        final FCPPluginMessage result =
            request != null ? FCPPluginMessage.constructSuccessReply(request) :
                              FCPPluginMessage.construct();
        
        result.params.putOverwrite("Message", "Scores");
   
		// TODO: Optimization: Remove this lock if it works without it.
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
					
					addIdentityFields(sfs, identity, "", suffix); // TODO: As of 2013-10-24, this is deprecated code to support old FCP clients. Remove it after some time.
					addIdentityFields(sfs, identity, "Identities." + suffix + ".", "");
					
					addScoreFields(sfs, score, suffix); // TODO: As of 2013-10-25, this is deprecated code to support old FCP clients. Remove it after some time.
					handleGetScore(sfs, score, suffix);
					
					if(includeTrustValue) {
			            Trust trust = null;
						try {
							trust = mWoT.getTrust(scoreOwner, identity);
						} catch(NotTrustedException e) {}
						
						addTrustFields(sfs, trust, suffix); // TODO: As of 2013-10-25, this is deprecated code to support old FCP clients. Remove it after some time.
						handleGetTrust(sfs, trust, suffix);
					}
					
					if(truster == null) // TODO: As of 2013-10-25, this is deprecated code to support old FCP clients. Remove it after some time.
		    			sfs.putOverwrite("ScoreOwner" + i, scoreOwner.getID());
					
					++i;
				}
			}
			
			sfs.put("Amount", i);
			sfs.put("Identities.Amount", i);
		}
		
		return sfs;
    }

    private SimpleFieldSet handleGetTrusters(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
    	final String context = getMandatoryParameter(params, "Context");
    	
        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "Identities");
        
        final boolean getAll = context.equals("");
        
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
        	
    		synchronized(mWoT) {
        		result = mWoT.getReceivedTrusts(mWoT.getIdentityByID(identityID), select).size();
        	}
        } else {
        	synchronized(mWoT) {
        		result = mWoT.getReceivedTrusts(mWoT.getIdentityByID(identityID)).size();
        	}
        }
    	
        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "TrustersCount");
        sfs.put("Value", result);
        return sfs;
    }

    private SimpleFieldSet handleGetTrustees(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException {
    	final String identityID = getMandatoryParameter(params, "Identity");
    	final String context = getMandatoryParameter(params, "Context");

    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "Identities");
        
        final boolean getAll = context.equals("");

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
        	
    		synchronized(mWoT) {
        		result = mWoT.getGivenTrusts(mWoT.getIdentityByID(identityID), select).size();
        	}
        } else {
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
    	
    	List<IntroductionPuzzle> puzzles = mWoT.getIntroductionClient().getPuzzles(mWoT.getOwnIdentityByID(identityID), PuzzleType.valueOf(type), amount);
    	
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
    	
    	IntroductionPuzzle puzzle = mWoT.getIntroductionPuzzleStore().getByID(puzzleID);
    	    	
    	final SimpleFieldSet result = new SimpleFieldSet(true);
    	result.putOverwrite("Message", "IntroductionPuzzle");
    	result.putOverwrite("Type", puzzle.getType().toString());
    	result.putOverwrite("MimeType", puzzle.getMimeType());
    	result.putOverwrite("Data", Base64.encodeStandard(puzzle.getData()));
    	return result;
    }
    
    private SimpleFieldSet handleSolveIntroductionPuzzle(final SimpleFieldSet params) throws InvalidParameterException, UnknownIdentityException, UnknownPuzzleException {
    	final String identityID = getMandatoryParameter(params, "Identity");
    	final String puzzleID = getMandatoryParameter(params, "Puzzle");
    	final String solution = getMandatoryParameter(params, "Solution");
    	
    	// We do not have to take locks here. TODO: Write a solvePuzzle which only takes IDs, it re-queries the objects anyway
    	mWoT.getIntroductionClient().solvePuzzle(
    			mWoT.getOwnIdentityByID(identityID), mWoT.getIntroductionPuzzleStore().getByID(puzzleID), solution);
    	
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	sfs.putOverwrite("Message", "PuzzleSolved");
    	return sfs;
    }
    
    /**
     * Processes the "Subscribe" FCP message, filing a {@link Subscription} to event-{@link Notification}s via {@link SubscriptionManager}.
     * <b>Required fields:</b>
     * "To" = "Identities" or "Trusts" or "Scores" - chooses among {@link IdentitiesSubscription} / {@link TrustsSubscription} /
     * {@link ScoresSubscription}.
     * 
     * FIXME: The following JavaDoc already mentions how this function will work when being
     * adapted to fred plugin-fcp-rewrite, but the function itself was not changed yet. Please
     * implement what the JavaDoc says.
     * 
     * <b>Reply:</b>
     * The reply consists of two separate FCP messages:<br>
     * The first message is "Message" = "Identities" or "Trusts" or "Scores". It is referenced
     * as "synchronization message" in the following text.<br>
     * Its {@link FCPPluginMessage#identifier} will <b>not</b> match the identifier of the original
     * "Subscribe" message you sent, i.e. not be a reply to it. (The reply to the original message
     * will be the second message which WOT will send.)<br><br>
     * 
     * The synchronization message contains the full dataset of the type you have subscribed to.
     * For the format of the message contents, see {@link #sendAllIdentities(String)} /
     * {@link #sendAllTrustValues(String)} / {@link #sendAllScoreValues(String)}.<br>
     * By storing this dataset, your client is completely synchronized with WOT. Upon changes of
     * anything, WOT will only have to send the single {@link Identity}/{@link Trust}/{@link Score}
     * object which has changed for your client to be fully synchronized again.<br><br>
     * 
     * You must indicate whether processing of the synchronization message succeeded or failed by 
     * replying with a {@link FCPPluginMessage} with {@link FCPPluginMessage#success} set
     * appropriately.<br>
     * This reply shall use the same {@link FCPPluginMessage#identifier} as the synchronization
     * message of WOT, or in other words be constructed as a reply to it using
     * {@link FCPPluginMessage#constructReplyMessage(FCPPluginMessage, SimpleFieldSet, Bucket,
     * boolean, String, String)} (or one of its shortcuts).
     * <br>This allows your client to be programmed in a transactional style: If part of the
     * transaction which stores the dataset fails, you can just roll it back and signal the error
     * to WOT by returning a success = false reply.<br><br>
     * 
     * After you have sent a reply to the synchronization message, WOT will send the second
     * message. This will have the same {@link FCPPluginMessage#identifier} as the
     * original "Subscribe" message which you first sent to subscribe, or in other words be the
     * reply to the original "Subscribe" message.<br>
     * 
     * If you indicated success in the synchronization message, the second message may indicate
     * success = true, which means that the subscription is active, and will be formatted as:
     * <br>
     * "Message" = "Subscribed"<br>
     * "SubscriptionID" = Random {@link UUID} of the Subscription.<br>
     * "To" = Same as the "To" field of your original message.<br><br>
     * 
     * <b>Errors</b>:<br>
     * If you indicated failure in the reply to the synchronization message by setting
     * {@link FCPPluginMessage#success}=false, the subscription is <b>not</b> filed. You will
     * receive the second message formatted as:<br>
     * {@link FCPPluginMessage#identifier} = same as of your "Subscribe" message<br>
     * {@link FCPPluginMessage#success} = false<br>
     * {@link FCPPluginMessage#errorCode} = "InternalError"<br>
     * {@link FCPPluginMessage#params}:<br>
     * "Message" = "Error"<br>
     * "OriginalMessage" = "Subscribe"<br><br>
     * 
     * If you are already subscribed to the selected type, you will only receive a single message:
     * {@link FCPPluginMessage#identifier} = same as of your "Subscribe" message<br>
     * {@link FCPPluginMessage#success} = false<br>
     * {@link FCPPluginMessage#errorCode} = "SubscriptionExistsAlready"<br>
     * {@link FCPPluginMessage#params}:<br>
     * "Message" = "Error"<br>
     * "SubscriptionID" = Same as in the original "Subscribed" message<br>
     * "To" = Same as you requested<br>
     * "OriginalMessage" = "Subscribe"<br><br>
     * 
     * <b>{@link Notification}s:</b>
     * Further  messages will be sent at any time in the future if an {@link Identity} / {@link Trust} / {@link Score}
     * object has changed. They will contain the version of the object before the change and after the change. For the format, see:
     * {@link #sendIdentityChangedNotification(String, IdentityChangedNotification)} /
     * {@link #sendTrustChangedNotification(String, TrustChangedNotification)} /
     * {@link #sendScoreChangedNotification(String, ScoreChangedNotification)}.
     * These messages are also send with the <b>synchronous</b> FCP API. In opposite to the initial synchronization message, by replying with
     * failure to the synchronous FCP call, you can signal that you want to receive the same notification again.
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
     * please always unsubscribe when possible.
     * 
     * @see SubscriptionManager#subscribeToIdentities(String) The underlying implementation for "To" = "Identities"
     * @see SubscriptionManager#subscribeToScores(String) The underyling implementation for "To" = "Trusts"
     * @see SubscriptionManager#subscribeToTrusts(String) The underlying implementation for "To" = "Scores"
     */
    private FCPPluginMessage handleSubscribe(final FCPPluginClient client, final FCPPluginMessage message) throws InvalidParameterException {
        final String to = getMandatoryParameter(message.params, "To");

    	
    	try {
            FCPPluginMessage reply = FCPPluginMessage.constructSuccessReply(message);
            Subscription<? extends Notification> subscription;
            
	    	if(to.equals("Identities")) {
	    		subscription = mSubscriptionManager.subscribeToIdentities(client.getID());
	    	} else if(to.equals("Trusts")) {
	    		subscription = mSubscriptionManager.subscribeToTrusts(client.getID());
	    	} else if(to.equals("Scores")) {
	    		subscription = mSubscriptionManager.subscribeToScores(client.getID());
	    	} else
	    		throw new InvalidParameterException("Invalid subscription type specified: " + to);
	    	
	    	SimpleFieldSet sfs = reply.params;
	    	sfs.putOverwrite("Message", "Subscribed");
	    	sfs.putOverwrite("SubscriptionID", subscription.getID());
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
     * Handles the "Unsubscribe" message, the inverse operation to the "Subscribe". See {@link #handleSubscribe(PluginReplySender, SimpleFieldSet)}.
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
    	final Class<Subscription<? extends Notification>> clazz = mSubscriptionManager.unsubscribe(subscriptionID);
        return handleUnsubscribe(request, clazz, subscriptionID);
    }
    
    public void sendUnsubscribedMessage(final UUID clientID,
            final Class<Subscription<? extends Notification>> clazz, final String subscriptionID)
                throws IOException {
        
        mPluginRespirator.getPluginClientByID(clientID).send(SendDirection.ToClient,
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
        final Class<Subscription<? extends Notification>> clazz, final String subscriptionID) {
        
        final FCPPluginMessage result =
            request != null ? FCPPluginMessage.constructSuccessReply(request) :
                              FCPPluginMessage.construct();
        
    	final String type;
    	
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
    
    public void sendAllIdentities(UUID clientID)
            throws FCPCallFailedException, IOException, InterruptedException {
        
        FCPPluginMessage reply = mPluginRespirator.getPluginClientByID(clientID).sendSynchronous(
            SendDirection.ToClient,
            handleGetIdentities(null),
            /* Large timeout since we possibly send _everything_.
             * Notice that a client can at maximum subscribe to Identities, Trusts and Scores in
             * parallel so there can be a maximum of 3 threads blocked in this large timeout
             * per client - sendAllIdentites(), sendAllTrustValues(), sendAllScoreValues().
             * That is an acceptable amount of threads per client, given that it only happens once
             * at the beginning of a connection. */
            TimeUnit.MINUTES.toNanos(SUBSCRIPTION_SYNCHRONIZATION_TIMEOUT_MINUTES));
        
        if(reply.success == false)
            throw new FCPCallFailedException(reply);
    }
    
    public void sendAllTrustValues(UUID clientID)
            throws FCPCallFailedException, IOException, InterruptedException {
        
        FCPPluginMessage reply = mPluginRespirator.getPluginClientByID(clientID).sendSynchronous(
            SendDirection.ToClient,
            handleGetTrusts(null),
            /* Large timeout since we possibly send _everything_.
             * Notice that a client can at maximum subscribe to Identities, Trusts and Scores in
             * parallel so there can be a maximum of 3 threads blocked in this large timeout
             * per client - sendAllIdentites(), sendAllTrustValues(), sendAllScoreValues().
             * That is an acceptable amount of threads per client, given that it only happens once
             * at the beginning of a connection. */
            TimeUnit.MINUTES.toNanos(SUBSCRIPTION_SYNCHRONIZATION_TIMEOUT_MINUTES));
        
        if(reply.success == false)
            throw new FCPCallFailedException(reply);
    }
    
    public void sendAllScoreValues(UUID clientID)
            throws FCPCallFailedException, IOException, InterruptedException {
        
    
        FCPPluginMessage reply = mPluginRespirator.getPluginClientByID(clientID).sendSynchronous(
            SendDirection.ToClient,
            handleGetScores(null),
            /* Large timeout since we possibly send _everything_.
             * Notice that a client can at maximum subscribe to Identities, Trusts and Scores in
             * parallel so there can be a maximum of 3 threads blocked in this large timeout
             * per client - sendAllIdentites(), sendAllTrustValues(), sendAllScoreValues().
             * That is an acceptable amount of threads per client, given that it only happens once
             * at the beginning of a connection. */
            TimeUnit.MINUTES.toNanos(SUBSCRIPTION_SYNCHRONIZATION_TIMEOUT_MINUTES));
        
        if(reply.success == false)
            throw new FCPCallFailedException(reply);
    }
    
    /**
     * @see SubscriptionManager.IdentityChangedNotification
     */
    public void sendIdentityChangedNotification(final UUID clientID,
            final IdentityChangedNotification notification)
                throws FCPCallFailedException, IOException, InterruptedException {
        
    	final SimpleFieldSet oldIdentity = handleGetIdentity((Identity)notification.getOldObject(), null);
    	final SimpleFieldSet newIdentity = handleGetIdentity((Identity)notification.getNewObject(), null);
    	
        sendChangeNotification(clientID, "IdentityChangedNotification", oldIdentity, newIdentity);
    }
    
    /**
     * @see SubscriptionManager.TrustChangedNotification
     */
    public void sendTrustChangedNotification(final UUID clientID,
            final TrustChangedNotification notification)
                throws FCPCallFailedException, IOException, InterruptedException {
        
    	final SimpleFieldSet oldTrust = handleGetTrust(new SimpleFieldSet(true), (Trust)notification.getOldObject(), "0");
    	final SimpleFieldSet newTrust = handleGetTrust(new SimpleFieldSet(true), (Trust)notification.getNewObject(), "0");

        sendChangeNotification(clientID, "TrustChangedNotification", oldTrust, newTrust);
    }
    
    /**
     * @see SubscriptionManager.ScoreChangedNotification
     */
    public void sendScoreChangedNotification(final UUID clientID,
            final ScoreChangedNotification notification)
                throws FCPCallFailedException, IOException, InterruptedException {
        
    	final SimpleFieldSet oldScore = handleGetScore(new SimpleFieldSet(true), (Score)notification.getOldObject(), "0");
    	final SimpleFieldSet newScore = handleGetScore(new SimpleFieldSet(true), (Score)notification.getNewObject(), "0");

        sendChangeNotification(clientID, "ScoreChangedNotification", oldScore, newScore);
    }
    
    private void sendChangeNotification(final UUID clientID, final String message,
            final SimpleFieldSet beforeChange, final SimpleFieldSet afterChange)
                throws FCPCallFailedException, IOException, InterruptedException {
        
        // Not a reply to an existing message since it is sent due to an event, not a client message
        final FCPPluginMessage fcpMessage = FCPPluginMessage.construct();
        
        fcpMessage.params.putOverwrite("Message", message);
        fcpMessage.params.put("BeforeChange", beforeChange);
        fcpMessage.params.put("AfterChange", afterChange);
        
        final FCPPluginMessage reply = mPluginRespirator.getPluginClientByID(clientID)
            .sendSynchronous(
                SendDirection.ToClient,
                fcpMessage,
                TimeUnit.MINUTES.toNanos(SUBSCRIPTION_NOTIFICATION_TIMEOUT_MINUTES));
        
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
        /* sfs.put("Message.DeprecatedField", true); */
        
        sfs.putOverwrite("Description", errorMessage);
        // Deprecated because there is FCPPluginMessage.errorMessage now
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
