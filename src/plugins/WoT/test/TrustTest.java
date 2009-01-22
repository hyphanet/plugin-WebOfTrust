/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.test;

import java.net.MalformedURLException;

import plugins.WoT.Identity;
import plugins.WoT.Trust;
import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.exceptions.UnknownIdentityException;

import com.db4o.Db4o;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class TrustTest extends DatabaseBasedTest {
	
	private String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	private Identity a;
	private Identity b;


	protected void setUp() throws Exception {
		super.setUp();

		a = new Identity(uriA, "A", true);
		b = new Identity(uriB, "B", true);
		Trust trust = new Trust(a,b,(byte)100,"test");
		db.store(trust);
		db.store(a);
		db.store(b);
		db.commit();
	}

	public void testTrust() throws InvalidParameterException, NotTrustedException, DuplicateTrustException {

		Trust trust = a.getGivenTrust(b, db);
		assertTrue(trust.getTruster() == a);
		assertTrue(trust.getTrustee() == b);
		assertTrue(trust.getValue() == 100);
		assertTrue(trust.getComment().equals("test"));
	}
	
	public void testTrustPersistence() throws MalformedURLException, UnknownIdentityException, DuplicateIdentityException, NotTrustedException, DuplicateTrustException  {
		
		db.close();
		
		System.gc();
		System.runFinalization();
		
		db = Db4o.openFile(getDatabaseFilename());
		
		a = Identity.getByURI(db, uriA);
		b = Identity.getByURI(db, uriB);
		Trust trust = a.getGivenTrust(b, db);
		
		assertTrue(trust.getTruster() == a);
		assertTrue(trust.getTrustee() == b);
		assertTrue(trust.getValue() == 100);
		assertTrue(trust.getComment().equals("test"));
	}
}
