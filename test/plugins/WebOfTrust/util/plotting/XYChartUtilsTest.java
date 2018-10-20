package plugins.WebOfTrust.util.plotting;

import static java.lang.Math.PI;
import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static plugins.WebOfTrust.util.CollectionUtil.array;
import static plugins.WebOfTrust.util.MathUtil.equalsApprox;
import static plugins.WebOfTrust.util.Pair.pair;
import static plugins.WebOfTrust.util.plotting.XYChartUtils.differentiate;
import static plugins.WebOfTrust.util.plotting.XYChartUtils.movingAverage;

import java.util.Iterator;

import org.junit.Test;

import plugins.WebOfTrust.AbstractJUnit4BaseTest;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.util.Pair;
import plugins.WebOfTrust.util.plotting.XYChartUtils.TimeChart;

public final class XYChartUtilsTest extends AbstractJUnit4BaseTest {

	@Test public void testMovingAverage() {
		TimeChart<Double> data = new TimeChart<>(128);
		data.addAll(asList(array(
			pair( 1d, 11d),
			pair( 2d, 12d),
			pair( 3d, 13d),
			pair( 4d, 14d),
			pair( 5d, 15d),
			pair( 6d, 16d),
			pair( 7d, 17d),
			pair( 8d, 18d),
			pair( 9d, 19d),
			pair(10d, 20d),
			pair(11d, 21d),
			pair(12d, 22d),
			pair(13d, 23d),
			pair(14d, 24d),
			pair(15d, 25d),
			pair(16d, 26d)
		)));
		
		TimeChart<Double> average = movingAverage(data, 1);
		// For the first 16 elements only one average will be yielded as the minimum window size
		// is not only the given amount of seconds but also 16 elements.
		assertEquals(1, average.size());
		Pair<Double, Double> a1 = average.peekFirst();
		assertEqualsApprox( 8.5d, a1.x, 99.999d);
		assertEqualsApprox(18.5d, a1.y, 99.999d);
		
		fail("Implement rest of this: Test what happens with more than 16 elements.");
	}

	@Test public void testDifferentiate() {
		TimeChart<Double> sinus = new TimeChart<>(1024);
		
		for(int i = 0; i < 1024; ++i) {
			double x = (2*PI / 1024) * i;
			double y = sin(x);
			sinus.addLast(new Pair<>(x, y));
			assertEquals(i+1, sinus.size());
		}
		
		TimeChart<Double> differentials = differentiate(sinus);
		assertEquals(sinus.size() - 1, differentials.size());
		
		Iterator<Pair<Double, Double>> iter = differentials.iterator();
		for(int i = 0; i < 1024-1; ++i) {
			double x1 = (2*PI / 1024) * i;
			double x2 = (2*PI / 1024) * (i+1);
			double x = (x1+x2) / 2;
			double y = cos(x); // d/dx sin(x) = cos(x)
			
			Pair<Double, Double> p = iter.next();
			assertEqualsApprox(x, p.x, 99.999d);
			assertEqualsApprox(y, p.y, 99.999d);
		}
	}

	@Test public void testMultiplyY() {
		fail("Not yet implemented");
	}

	@Override protected WebOfTrust getWebOfTrust() {
		return null;
	}

}
