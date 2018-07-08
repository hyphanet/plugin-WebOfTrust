package plugins.WebOfTrust.util.plotting;

import static java.lang.Double.isInfinite;
import static java.lang.Double.isNaN;
import static java.lang.Math.abs;
import static java.lang.Math.round;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Iterator;

import org.knowm.xchart.XYChart;

import plugins.WebOfTrust.util.LimitedArrayDeque;
import plugins.WebOfTrust.util.Pair;
import freenet.support.CurrentTimeUTC;

/**
 * Utility classes to preprocess data to make it suitable for plotting, and to plot it using the
 * XChart library's {@link XYChart}. */
public final class XYChartUtils {
	/**
	 * Input:
	 * - a {@link LimitedArrayDeque} of {@link Pair}s where {@link Pair#x} is a
	 *   {@link CurrentTimeUTC#getInMillis()} timestamp and {@link Pair#y} is an arbitrary
	 *   {@link Number} which supports {@link Number#doubleValue()}.
	 *   This can be interpreted as a series of measurements which shall be preprocessed by this
	 *   function in order to later on create an {@link XYChart}.
	 * - an integer threshold of seconds.
	 * 
	 * Output:
	 * A new LimitedArrayDeque is returned where each contained Pair contains the average X- and
	 * Y-values of the Pairs in the source dataset across the given amount of seconds.
	 * I.e. it splits the given dataset into slots of the given amount of seconds, builds the
	 * average X/Y-value across those slots, and for each slot returns a single Pair.
	 * 
	 * If a slot contains less than 16 Pairs it is extended until it contains at least 16.
	 * I.e. a single result slot must contain the average of at least 16 Pairs.
	 * This is done in order to prevent the resulting plot from being jumpy in areas of few
	 * measurements.
	 * 
	 * If there aren't even 16 measurements in the input dataset the result is not empty, a single
	 * Pair is returned to contain the average of the given data.
	 * This ensures code which processes the result does not have to contain code for handling
	 * emptiness if the input data is never empty. */
	public static final <T extends Number> LimitedArrayDeque<Pair<Long, Double>> average(
			LimitedArrayDeque<Pair<Long, T>> xyData, int seconds) {
		
		assert(xyData.size() > 0);
		assert(seconds > 0);
		
		LimitedArrayDeque<Pair<Long, Double>> result
			= new LimitedArrayDeque<>(xyData.sizeLimit());
		
		if(xyData.size() < 2)
			return result;
		
		Pair<Long, T> startOfAverage = xyData.peekFirst();
		double xAverage = 0;
		double yAverage = 0;
		int amount = 0;
		for(Pair<Long, T> cur : xyData) {
			if((cur.x - startOfAverage.x) <= SECONDS.toMillis(seconds) || amount < 16) {
				// Undo previous averaging
				xAverage *= amount;
				yAverage *= amount;
				
				// Compute new average. We must divide at every added item instead of only dividing
				// after the last because the values may be so large that they cause overflow or
				// imprecision if we keep adding them up until the end.
				xAverage += cur.x.doubleValue();
				yAverage += cur.y.doubleValue();
				++amount;
				xAverage /= amount;
				yAverage /= amount;
			} else {
				assert(xAverage <= (startOfAverage.x + SECONDS.toMillis(seconds))
					|| amount == 16);
				
				result.addLast(new Pair<>(round(xAverage), yAverage));
				
				startOfAverage = cur;
				xAverage = 0;
				yAverage = 0;
				amount = 0;
			}
		}
		
		// If there is remaining data add it to the result if it contains at least the minimum
		// amount of measurements.
		// But if the result set is empty then ignore the minimum amount so we never return an
		// empty result.
		if(amount >= 16 || result.size() == 0)
			result.addLast(new Pair<>(round(xAverage), yAverage));
		
		return result;
	}

	/**
	 * Consumes a {@link LimitedArrayDeque} of {@link Pair}s where {@link Pair#x} is a
	 * {@link CurrentTimeUTC#getInMillis()} timestamp and {@link Pair#y}
	 * is an arbitrary {@link Number} which supports {@link Number#doubleValue()}.
	 * This can be interpreted as a series of measurements which shall be preprocessed by this
	 * function in order to later on create an {@link XYChart}.
	 * 
	 * Returns a new LimitedArrayDeque which contains the dy/dx of the given plot data.
	 * 
	 * The resulting dataset will be smaller than the input. */
	public static final <T extends Number> LimitedArrayDeque<Pair<Long, Double>> differentiate(
			LimitedArrayDeque<Pair<Long, T>> xyData) {
		
		assert(xyData.size() >= 2);
		
		LimitedArrayDeque<Pair<Long, Double>> result =
			new LimitedArrayDeque<>(xyData.sizeLimit());
		
		if(xyData.size() < 2)
			return result;
		
		Iterator<Pair<Long, T>> i = xyData.iterator();
		Pair<Long, T> prev = i.next();			
		do {
			Pair<Long, T> cur = i.next();
			
			long  dx = cur.x - prev.x;
			// Avoid division by zero in dy/dx.
			if(abs(dx) <= Double.MIN_VALUE)
				continue;
			
			double dy = cur.y.doubleValue() - prev.y.doubleValue();
			
			Long   x = prev.x;
			double y = dy / dx;
			
			assert(!isInfinite(y) && !isNaN(y)) : "Division by zero!";
			
			result.addLast(new Pair<>(x, y));
			prev = cur;
		} while(i.hasNext());
		
		return result;
	}
}
