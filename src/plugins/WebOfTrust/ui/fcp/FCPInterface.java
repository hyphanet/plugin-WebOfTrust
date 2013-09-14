/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.fcp;

import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.SubscriptionManager.IdentityChangedNotification;
import plugins.WebOfTrust.SubscriptionManager.Notification;
import plugins.WebOfTrust.SubscriptionManager.ScoreChangedNotification;
import plugins.WebOfTrust.SubscriptionManager.Subscription;
import plugins.WebOfTrust.SubscriptionManager.SubscriptionExistsAlreadyException;
import plugins.WebOfTrust.SubscriptionManager.TrustChangedNotification;
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
import freenet.pluginmanager.FredPluginFCP;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginReplySender;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public final class FCPInterface implements FredPluginFCP {

    private final WebOfTrust mWoT;
    
    private final SubscriptionManager mSubscriptionManager;
    
    /**
     * Contains all clients which ever subscribed to content.
     * Key = replySender.getPluginName() + ";" + replySender.getIdentifier()
     * FIXME: We should have a proper disconnection mechanism instead of using WeakReferences.
     */
    private final HashMap<String, WeakReference<PluginReplySender>> mClients = new HashMap<String, WeakReference<PluginReplySender>>();

    public FCPInterface(final WebOfTrust myWoT) {
        mWoT = myWoT;
        mSubscriptionManager = mWoT.getSubscriptionManager();
    }

    public void handle(final PluginReplySender replysender, final SimpleFieldSet params, final Bucket data, final int accesstype) {

        try {
            final String message = params.get("Message");
            
            // TODO: Optimization: This should use a HashMap<String, HandleInterface> instead of zillions of equals()
            
            if (message.equals("GetTrust")) {
                replysender.send(handleGetTrust(params), data);
            } else if(message.equals("GetScore")) {
            	replysender.send(handleGetScore(params), data);
            }else if (message.equals("CreateIdentity")) {
                replysender.send(handleCreateIdentity(params), data);
            } else if (message.equals("SetTrust")) {
                replysender.send(handleSetTrust(params), data);
            } else if (message.equals("RemoveTrust")) {
            	  replysender.send(handleRemoveTrust(params), data);
            } else if (message.equals("AddIdentity")) {
                replysender.send(handleAddIdentity(params), data);
            } else if (message.equals("GetIdentity")) {
                replysender.send(handleGetIdentity(params), data);
            } else if (message.equals("GetOwnIdentities")) {
                replysender.send(handleGetOwnIdentities(params), data);
            } else if (message.equals("GetIdentities")) {
            	replysender.send(handleGetIdentities(params), data);
            } else if (message.equals("GetTrusts")) {
            	replysender.send(handleGetTrusts(params), data);
            } else if (message.equals("GetScores")) {
            	replysender.send(handleGetScores(params), data);
            } else if (message.equals("GetIdentitiesByScore")) {
                replysender.send(handleGetIdentitiesByScore(params), data);
            } else if (message.equals("GetTrusters")) {
                replysender.send(handleGetTrusters(params), data);
            } else if (message.equals("GetTrustersCount")) {
            	replysender.send(handleGetTrustersCount(params), data);
            } else if (message.equals("GetTrustees")) {
                replysender.send(handleGetTrustees(params), data);
            } else if (message.equals("GetTrusteesCount")) {
            	replysender.send(handleGetTrusteesCount(params), data);
            } else if (message.equals("AddContext")) {
                replysender.send(handleAddContext(params), data);
            } else if (message.equals("RemoveContext")) {
                replysender.send(handleRemoveContext(params), data);
            } else if (message.equals("SetProperty")) {
                replysender.send(handleSetProperty(params), data);
            } else if (message.equals("GetProperty")) {
                replysender.send(handleGetProperty(params), data);
            } else if (message.equals("RemoveProperty")) {
                replysender.send(handleRemoveProperty(params), data);
            } else if (message.equals("GetIntroductionPuzzles")) {
            	replysender.send(handleGetIntroductionPuzzles(params), data);
            } else if (message.equals("GetIntroductionPuzzle")) {
            	replysender.send(handleGetIntroductionPuzzle(params), data);
            } else if (message.equals("SolveIntroductionPuzzle")) {
            	replysender.send(handleSolveIntroductionPuzzle(params), data);
            } else if (message.equals("Subscribe")) {
            	replysender.send(handleSubscribe(replysender, params), data);
            } else if (message.equals("Unsubscribe")) {
            	replysender.send(handleUnsubscribe(params), data);
            } else if (message.equals("Ping")) {
            	replysender.send(handlePing(), data);
            } else if (message.equals("RandomName")) {
            	replysender.send(handleRandomName(params), data);
            } else {
                throw new Exception("Unknown message (" + message + ")");
            }
        } catch (final Exception e) {
        	// TODO: This might miss some stuff which are errors. Find a better way of detecting which exceptions are okay.
        	boolean dontLog = e instanceof NoSuchContextException ||
        						e instanceof NotInTrustTreeException ||
        						e instanceof NotTrustedException ||
        						e instanceof UnknownIdentityException ||
        						e instanceof UnknownPuzzleException;
        	
        	if(!dontLog)
        		Logger.error(this, "FCP error", e);
        	
            try {
                replysender.send(errorMessageFCP(params.get("Message"), e), data);
            } catch (final PluginNotFoundException e1) {
                Logger.normal(this, "Connection to request sender lost", e1);
            }
        }
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
    
    private SimpleFieldSet handleGetTrust(final SimpleFieldSet params) throws InvalidParameterException, NotTrustedException, UnknownIdentityException {
    	final String trusterID = getMandatoryParameter(params, "Truster");
    	final String trusteeID = getMandatoryParameter(params, "Trustee");
    	
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	synchronized(mWoT) {
    		// TODO: Optimize by implementing https://bugs.freenetproject.org/view.php?id=6076
    		handleGetTrust(sfs, mWoT.getTrust(mWoT.getIdentityByID(trusterID), mWoT.getIdentityByID(trusteeID)), "0");
    	}
    	sfs.putOverwrite("Message", "Trust");
    	return sfs;
    }
    
    private SimpleFieldSet handleGetTrust(final SimpleFieldSet sfs, final Trust trust, String suffix) {
    	if(trust == null) {
    		sfs.putOverwrite("Value", "Inexistent");
    		return sfs;
    	}
    	
		sfs.putOverwrite("Truster" + suffix, trust.getTruster().getID());
		sfs.putOverwrite("Trustee" + suffix, trust.getTrustee().getID());
		sfs.putOverwrite("Value" + suffix, Byte.toString(trust.getValue()));
		sfs.putOverwrite("Comment" + suffix, trust.getComment());
		return sfs;
    }
    
    private SimpleFieldSet handleGetScore(final SimpleFieldSet params) throws NotInTrustTreeException, UnknownIdentityException, InvalidParameterException {
    	final String trusterID = getMandatoryParameter(params, "Truster");
    	final String trusteeID = getMandatoryParameter(params, "Trustee");

    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	synchronized(mWoT) {
    		// TODO: Optimize by implementing https://bugs.freenetproject.org/view.php?id=6076
    		handleGetScore(sfs, mWoT.getScore(mWoT.getOwnIdentityByID(trusterID), mWoT.getIdentityByID(trusteeID)), "0");
    	}

    	sfs.putOverwrite("Message", "Score");
		return sfs;
    }
    
    private SimpleFieldSet handleGetScore(final SimpleFieldSet sfs, final Score score, final String suffix) {    	
    	if(score == null) {
    		sfs.putOverwrite("Value", "Inexistent");
    		return sfs;
    	}
    	
		sfs.putOverwrite("Truster" + suffix, score.getTruster().getID());
		sfs.putOverwrite("Trustee" + suffix, score.getTrustee().getID());
		sfs.putOverwrite("Value" + suffix, Integer.toString(score.getScore()));
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
    		sfs.putOverwrite("Message", (identity instanceof OwnIdentity) ? "OwnIdentity" : "Identity");
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
    		
    		addIdentityFields(sfs, identity, "0");
    		// TODO: As of 2013-08-02, this is legacy code to support old FCP clients. Remove it after some time.
            addIdentityFields(sfs, identity, "");

    		if(truster != null) {
            	addTrustFields(sfs, truster, identity, "0");
            	addScoreFields(sfs, truster, identity, "0");
            
    			// TODO: As of 2013-08-02, this is legacy code to support old FCP clients. Remove it after some time.
            	addTrustFields(sfs, truster, identity, "");
            	addScoreFields(sfs, truster, identity, "");
    		}
    	
		return sfs;
	}

    /**
     * Add fields describing the given identity:
     * 
     * TypeSUFFIX = type of the identity,  "Inexistent", "OwnIdentity" or "Identity".
     * If the Type is  "Inexistent", the identity does not exist anymore and no other fields will be present.
     * 
     * NicknameSUFFIX = nickname of the identity
     * RequestURISUFFIX = request URI of the identity
     * InsertURISUFFIX = insert URI of the identity. Only present if Type is OwnIdentity
     * PublishesTrustListSUFFIX = true/false if the identity does publish a trust list or not 
     * IdentitySUFFIX = ID of the identity (deprecated)
     * IDSUFFIX = ID of the identity
     * 
     * If suffix.isEmpty() is true:
     * ContextX = name of context with index X
     * PropertyX.Name = name of property with index X
     * PropertyX.Value = value of property with index X
     * 
     * If suffix.isEmpty() is false:
     * ContextsSUFFIX.ContextX = name of context with index X
     * PropertiesSUFFIX.PropertyX.Name = name of property X
     * PropertiesSUFFIX.PropertyX.Value = value of property X
     * 
     * Always:
     * ContextsSUFFIX.Amount = number of contexts
     * PropertiesSUFFIX.Amount = number of properties
     * 
     * @param sfs The {@link SimpleFieldSet} to add fields to.
     * @param identity The {@link Identity} to describe. Can be null to signal that the identity does not exist anymore.
     * @param suffix Added as descriptor for possibly multiple identities. Empty string is special case as explained in the function description.
     */
    private void addIdentityFields(SimpleFieldSet sfs, Identity identity, String suffix) {
    	if(identity == null) {
    		sfs.putOverwrite("Type" + suffix, "Inexistent");
    		return;
    	}
    	
    	sfs.putOverwrite("Type" + suffix, (identity instanceof OwnIdentity) ? "OwnIdentity" : "Identity");
        sfs.putOverwrite("Nickname" + suffix, identity.getNickname());
        sfs.putOverwrite("RequestURI" + suffix, identity.getRequestURI().toString());
        sfs.putOverwrite("Identity" + suffix, identity.getID()); // TODO: As of 2013-09-11, this is legacy code to support old FCP clients. Remove it after some time.
 		sfs.putOverwrite("ID" + suffix, identity.getID()); 
        sfs.put("PublishesTrustList" + suffix, identity.doesPublishTrustList());

 		if(identity instanceof OwnIdentity) {
 			OwnIdentity ownId = (OwnIdentity)identity;
 			sfs.putOverwrite("InsertURI" + suffix, ownId.getInsertURI().toString());
 		}
  
 		final ArrayList<String> contexts = identity.getContexts();
 		final HashMap<String, String> properties = identity.getProperties();
 		int contextCounter = 0;
 		int propertyCounter = 0;
 		
        if (suffix.isEmpty()) {	
            for(String context : contexts) {
                sfs.putOverwrite("Context" + contextCounter++, context);
            }
            
            for (Entry<String, String> property : properties.entrySet()) {
                sfs.putOverwrite("Property" + propertyCounter + ".Name", property.getKey());
                sfs.putOverwrite("Property" + propertyCounter++ + ".Value", property.getValue());
            }
        } else {        	
        	for(String context : contexts) {
                sfs.putOverwrite("Contexts" + suffix + ".Context" + contextCounter++, context);
            }
            
            for (Entry<String, String> property : properties.entrySet()) {
                sfs.putOverwrite("Properties" + suffix + ".Property" + propertyCounter + ".Name", property.getKey());
                sfs.putOverwrite("Properties" + suffix + ".Property" + propertyCounter++ + ".Value", property.getValue());
            }
        }
        
        sfs.put("Contexts" + suffix + ".Amount", contextCounter);
        sfs.put("Properties" + suffix + ".Amount", propertyCounter);
    }
    
    /**
     * Adds fields (currently only one) describing the trust value from the given truster to the given trustee:
     * 
     * TrustSUFFIX = Value of trust, from -100 to +100. "null" if no such trust exists.
     * 
     * @param suffix Added as descriptor for possibly multiple identities.
     */
    private void addTrustFields(SimpleFieldSet sfs, Identity truster, Identity trustee, String suffix) {
        try {
            final Trust trust = mWoT.getTrust(truster, trustee);
            sfs.putOverwrite("Trust" + suffix, Byte.toString(trust.getValue()));
        } catch (final NotTrustedException e1) {
            sfs.putOverwrite("Trust" + suffix, "null");
        }
    }
    
    /**
     * Adds field describing the given score value
     * 
     * ScoreSUFFIX = Integer value of the Score
     * RankSUFFIX = Integer value of the rank of the score.
     * 
     * @param suffix Added as descriptor for possibly multiple identities.
     */
    private void addScoreFields(SimpleFieldSet sfs, Score score, String suffix) {
        sfs.putOverwrite("Score" + suffix, Integer.toString(score.getScore()));
        sfs.putOverwrite("Rank" + suffix, Integer.toString(score.getRank()));
    }
    
    /**
     * Adds field describing the score value from the given truster to the given trustee.
     * 
     * ScoreSUFFIX = Integer value of the Score. "null" if no such score exists.
     * RankSUFFIX = Integer value of the rank of the score. "null" if no such score exists.
     * @param suffix Added as descriptor for possibly multiple identities.
     */
    private void addScoreFields(SimpleFieldSet sfs, OwnIdentity truster, Identity trustee, String suffix) {
        try {
            addScoreFields(sfs, mWoT.getScore(truster, trustee), suffix);
        } catch (final NotInTrustTreeException e) {
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
    
    private SimpleFieldSet handleGetIdentities(final SimpleFieldSet params) {
        final String context;
        
        if(params!= null) {
        	context = params.get("Context");
        } else {
        	context = null;
        }

		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Identities");
		
		// TODO: Optimization: Remove this lock if it works without it.
		synchronized(mWoT) {
			final boolean getAll = context == null || context.equals("");
	
			int i = 0;
			for(final Identity identity : mWoT.getAllIdentities()) {
				if(getAll || identity.hasContext(context)) {
					// TODO: Allow the client to select what data he wants
					addIdentityFields(sfs, identity, Integer.toString(i));
					
					++i;
				}
			}
			sfs.put("Amount", i);
		}
		
		return sfs;
    }
    
    private SimpleFieldSet handleGetTrusts(final SimpleFieldSet params) {
        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "Trusts");
   
		// TODO: Optimization: Remove this lock if it works without it.
        synchronized(mWoT) {
        	int i = 0;
			for(final Trust trust : mWoT.getAllTrusts()) {
				handleGetTrust(sfs, trust, Integer.toString(i));
				++i;
			}
        	sfs.put("Amount", i);
        }
        
        return sfs;
    }
    
    private SimpleFieldSet handleGetScores(final SimpleFieldSet params) {
        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "Scores");
   
		// TODO: Optimization: Remove this lock if it works without it.
        synchronized(mWoT) {
        	int i = 0;
			for(final Score score: mWoT.getAllScores()) {
				handleGetScore(sfs, score, Integer.toString(i));
				
				++i;
			}
			sfs.put("Amount", i);
        }
        
        return sfs;
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
					
					addIdentityFields(sfs, identity, suffix);
					addScoreFields(sfs, score, suffix);
					
					if(includeTrustValue)
						addTrustFields(sfs, scoreOwner, identity, suffix);
					
					if(truster == null)
		    			sfs.putOverwrite("ScoreOwner" + i, scoreOwner.getID());
					
					++i;
				}
			}
			
			sfs.put("Amount", i);
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
        final String nameType = params.get("type");

        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "Name");
	if ("Nickname".equals(nameType)) {
	    sfs.putOverwrite("Name", RandomName.newNickname());
	}
	else if ("UnprotectedName".equals(nameType)) {
	    sfs.putOverwrite("Name", RandomName.newUnprotectedName());
	}
	else {
	    sfs.putOverwrite("Name", RandomName.newName());
	}
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
     * @see SubscriptionManager#subscribeToIdentities(String)
     * @see SubscriptionManager#subscribeToScores(String)
     * @see SubscriptionManager#subscribeToTrusts(String)
     */
    private SimpleFieldSet handleSubscribe(final PluginReplySender replySender, final SimpleFieldSet params) throws InvalidParameterException, SubscriptionExistsAlreadyException {
    	final String to = getMandatoryParameter(params, "To");
    	
    	// - We not only use the Identifier which the plugin provided but also the hashCode of the replySender:
    	// We need the ID to NOT randomly match the ID of a plugin before it was restarted.
    	// This is necessary because subscribe functions will throw SubscriptionExistsAlreadyException
    	// if a similar subscription with the same fcpID exists.
    	// 
    	// - We use System.identityHashCode() to prevent PluginReplySender implementations from overriding hashCode() with a hashCode
    	// implementation which merely hashes the getPluginName() and getIdentifier() as we have already decided that they are not enough.
    	// 
    	// -We cannot just use a random ID because then a client could subscribe multiple times to the same type of subscription:
    	// The throwing of SubscriptionExistsAlready exception is there to prevent that.
    	// 
    	final String fcpID = replySender.getPluginName() + ";" + replySender.getIdentifier() + ";" + System.identityHashCode(replySender);

    	synchronized(mClients) {
    		// Don't check for existing entry: 
    		// - PluginTalker always uses the same PluginReplySender
    		// - The hasCode in the ID makes it very unlikely for two IDs of different PluginTalkers to collide
    		// - What could guarantee to prevent collisions even if the hashCode collides is that clients are allowed to uniquely
    		//   chose replySender.getIdentifier() - if client authors do not implement a unique identifier its their fault if stuff breaks.
    		mClients.put(fcpID, new WeakReference<PluginReplySender>(replySender));
    	}
    	
    	Subscription<? extends Notification> subscription;
    	
    	if(to.equals("Identities")) {
    		subscription = mSubscriptionManager.subscribeToIdentities(fcpID);
    	} else if(to.equals("Trusts")) {
    		subscription = mSubscriptionManager.subscribeToTrusts(fcpID);
    	} else if(to.equals("Scores")) {
    		subscription = mSubscriptionManager.subscribeToScores(fcpID);
    	} else
    		throw new InvalidParameterException("Invalid subscription type specified: " + to);
    		
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	sfs.putOverwrite("Message", "Subscribed");
    	sfs.putOverwrite("Subscription", subscription.getID());
    	return sfs;
    }
    
    private SimpleFieldSet handleUnsubscribe(final SimpleFieldSet params) throws InvalidParameterException, UnknownSubscriptionException {
    	final String subscriptionID = getMandatoryParameter(params, "SubscriptionID");
    	mSubscriptionManager.unsubscribe(subscriptionID);
    	
    	// FIXME: What about mClients?
    	
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	sfs.putOverwrite("Message", "Unubscribed");
    	sfs.putOverwrite("Subscription", subscriptionID);
    	return sfs;
    }
    
    private PluginReplySender getReplySender(String fcpID) throws PluginNotFoundException {
    	WeakReference<PluginReplySender> ref = mClients.get(fcpID);
    	PluginReplySender sender =  ref!= null ? ref.get() : null;
    	
    	if(sender == null)
    		throw new PluginNotFoundException();
    	
    	return sender;
    }
    
    public void sendAllIdentities(String fcpID) throws PluginNotFoundException {
    	getReplySender(fcpID).send(handleGetIdentities(null));
    }
    
    public void sendAllTrustValues(String fcpID) throws PluginNotFoundException {
    	getReplySender(fcpID).send(handleGetTrusts(null));
    }
    
    public void sendAllScoreValues(String fcpID) throws PluginNotFoundException {
    	getReplySender(fcpID).send(handleGetScores(null));
    }
    
    /**
     * @see SubscriptionManager.IdentityChangedNotification
     */
    public void sendIdentityChangedNotification(final String fcpID, final IdentityChangedNotification notification) throws PluginNotFoundException {
    	final SimpleFieldSet oldIdentity = handleGetIdentity((Identity)notification.getOldObject(), null);
    	final SimpleFieldSet newIdentity = handleGetIdentity((Identity)notification.getNewObject(), null);
    	
    	sendChangeNotification(fcpID, "IdentityChangedNotification", oldIdentity, newIdentity);
    }
    
    /**
     * @see SubscriptionManager.TrustChangedNotification
     */
    public void sendTrustChangedNotification(String fcpID, final TrustChangedNotification notification) throws PluginNotFoundException {
    	final SimpleFieldSet oldTrust = handleGetTrust(new SimpleFieldSet(true), (Trust)notification.getOldObject(), "0");
    	final SimpleFieldSet newTrust = handleGetTrust(new SimpleFieldSet(true), (Trust)notification.getNewObject(), "0");

    	sendChangeNotification(fcpID, "TrustChangedNotification", oldTrust, newTrust);
    }
    
    /**
     * @see SubscriptionManager.ScoreChangedNotification
     */
    public void sendScoreChangedNotification(String fcpID, final ScoreChangedNotification notification) throws PluginNotFoundException {
    	final SimpleFieldSet oldScore = handleGetScore(new SimpleFieldSet(true), (Score)notification.getOldObject(), "0");
    	final SimpleFieldSet newScore = handleGetScore(new SimpleFieldSet(true), (Score)notification.getNewObject(), "0");

    	sendChangeNotification(fcpID, "ScoreChangedNotification", oldScore, newScore);
    }
    
    private void sendChangeNotification(final String fcpID, final String message, final SimpleFieldSet beforeChange, final SimpleFieldSet afterChange) throws PluginNotFoundException {
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	sfs.putOverwrite("Message", message);;
    	sfs.put("BeforeChange", beforeChange);
    	sfs.put("AfterChange", afterChange);
    	
    	getReplySender(fcpID).send(sfs);
    }
    
    private SimpleFieldSet handlePing() {
    	final SimpleFieldSet sfs = new SimpleFieldSet(true);
    	sfs.putOverwrite("Message", "Pong");
    	return sfs;
    }

    private SimpleFieldSet errorMessageFCP(final String originalMessage, final Exception e) {
        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putOverwrite("Message", "Error");
        sfs.putOverwrite("OriginalMessage", originalMessage);
        sfs.putOverwrite("Description", e.toString());
        return sfs;
    }
  
}
