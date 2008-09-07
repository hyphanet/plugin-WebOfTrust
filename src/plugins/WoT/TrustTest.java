/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.io.File;

import junit.framework.TestCase;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class TrustTest extends TestCase {
	
	private String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	
	private Identity a;
	private Identity b;
	
	private ObjectContainer db;
	private boolean firstRun = false;
	
	public TrustTest(String name) {
		super(name);
	}
	
	protected void setUp() throws Exception {
		
		super.setUp();
		db = Db4o.openFile("trustTest.db4o");
		
		try {
			a = Identity.getByURI(db, uriA);
			b = Identity.getByURI(db, uriB);
		} catch (UnknownIdentityException e) {
			a = new Identity(uriA, "A", "true", "test");
			b = new Identity(uriB, "B", "true", "test");
			db.store(a);
			db.store(b);
			firstRun = true;
			System.out.println("First run of the trust test. Run it again to check data persistency");
		}
	}
	
	protected void tearDown() throws Exception {
		db.close();
		if(!firstRun) new File("trustTest.db4o").delete();
	}
	
	/*
	 * You have to run 'ant' twice in order to really perform this test. The goal is to check if db4o 
	 * is able to find a Trust object by its Identities pointers, even across Database restarts. 
	 */
	public void testTrust() throws InvalidParameterException, NotTrustedException, DuplicateTrustException {
		Trust trust;
		
		if(firstRun) {
			trust = new Trust(a,b,100,"test");
			db.store(trust);
		} else {
			trust = a.getGivenTrust(b, db);
		}
		assertTrue(trust.getTruster() == a);
		assertTrue(trust.getTrustee() == b);
		assertTrue(trust.getValue() == 100);
		assertTrue(trust.getComment().equals("test"));
	}
}
