package plugins.WebOfTrust.util.plotting;

import static java.lang.Double.isInfinite;
import static java.lang.Double.isNaN;
import static java.lang.Math.abs;
import static java.lang.Math.round;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.util.Iterator;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.QuickChart;
import org.knowm.xchart.XYChart;

import plugins.WebOfTrust.ui.web.StatisticsPage;
import plugins.WebOfTrust.ui.web.StatisticsPage.StatisticsPlotRenderer;
import plugins.WebOfTrust.util.LimitedArrayDeque;
import plugins.WebOfTrust.util.Pair;
import freenet.l10n.BaseL10n;
import freenet.support.CurrentTimeUTC;

/**
 * Utility classes to preprocess data to make it suitable for plotting, and to plot it using the
 * XChart library's {@link XYChart}.
 * Typically used by the web interface's {@link StatisticsPage} to render measurements.
 * 
 * The member functions usually consume a series of measurements where the X-value is the time, and
 * the Y-value is a measurement at the given time.
 * 
 * FIXME: Change all functions to consume a double of seconds since startup for the time instead
 * of the absolute time in milliseconds. This will both increase precision of the floating point
 * calculations as we convert to minutes/hours when rendering anyway, as well as make the functions
 * more readable. */
public final class XYChartUtils {

	/**
	 * Generic implementation of creating an {@link XYChart} where the X-axis is the time.
	 * Can be used by {@link StatisticsPlotRenderer} implementations for their purposes.
	 * 
	 * @param xyData The plot data. A {@link LimitedArrayDeque} of {@link Pair}s where
	 *     {@link Pair#x} is a {@link CurrentTimeUTC#getInMillis()} timestamp and {@link Pair#y} is
	 *     an arbitrary {@link Number} which supports {@link Number#doubleValue()}.
	 *     ATTENTION: It MUST always contain at least one entry.
	 * @param x0 The {@link CurrentTimeUTC#getInMillis()} of the x=0 origin of the plot. The time
	 *     labels on the X-axis will not be absolute time but a relative time offset, e.g.
	 *     "3 minutes". The offset is built against this initial UTC time. 
	 * @param l10n The {@link BaseL10n} used to translate the given string keys.
	 * @param title L10n key of the label on top of the plot.
	 * @param xLabelHours L10n key of the X-axis label if it is automatically chosen to display
	 *     hours.
	 * @param xLabelMinutes L10n key of the X-axis label if it is automatically chosen to display
	 *     minutes.
	 * @param yLabel L10n key of the Y-axis label.
	 * @return An image of the PNG format, serialized to a byte array. */
	public static final <T extends Number> byte[] getTimeBasedPlotPNG(
			LimitedArrayDeque<Pair<Long, T>> xyData, long x0, BaseL10n l10n,
			String title, String xLabelHours, String xLabelMinutes, String yLabel) {
		
		// If the amount of measurements we've gathered is at least 2 hours then we measure the
		// X-axis in hours, otherwise we measure it in minutes.
		// Using 2 hours instead of the more natural 1 hour because 1 hour measurements are a
		// typical benchmark of bootstrapping and I don't want to annoy people who want to measure
		// that with the X-axis not showing minutes.
		boolean hours = MILLISECONDS.toHours(
				(xyData.peekLast().x - xyData.peekFirst().x)
			) >= 2;
		
		double timeUnit = (hours ? HOURS : MINUTES).toMillis(1);
		double[] x = new double[xyData.size()];
		double[] y = new double[x.length];
		int i = 0;
		for(Pair<Long, T> p : xyData) {
			x[i] = ((double)(p.x - x0)) / timeUnit;
			y[i] = p.y.doubleValue();
			++i;
		}
		
		XYChart c = QuickChart.getChart(l10n.getString(title),
			l10n.getString(hours ? xLabelHours : xLabelMinutes),
			l10n.getString(yLabel), null, x, y);
		
		/* For debugging
			for(XYSeries s: c.getSeriesMap().values())
				s.setMarker(SeriesMarkers.CIRCLE);
		 */
		
		byte[] png;
		try {
			png = BitmapEncoder.getBitmapBytes(c, BitmapFormat.PNG);
		} catch (IOException e) {
			// No idea why this would happen so don't require callers to handle it by converting to
			// non-declared exception.
			throw new RuntimeException(e);
		}
		
		return png;
	}

	/**
	 * Input:
	 * - a {@link LimitedArrayDeque} of {@link Pair}s where {@link Pair#x} is a
	 *   {@link CurrentTimeUTC#getInMillis()} timestamp and {@link Pair#y} is an arbitrary
	 *   {@link Number} which supports {@link Number#doubleValue()}.
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

	/** FIXME: Document */
	public static final <T extends Number> LimitedArrayDeque<Pair<Long, Double>> movingAverage(
			LimitedArrayDeque<Pair<Long, T>> xyData, int seconds) {
		
		assert(xyData.size() > 1);
		assert(seconds > 0);
		
		LimitedArrayDeque<Pair<Long, Double>> result
			= new LimitedArrayDeque<>(xyData.sizeLimit());
		
		if(xyData.size() < 1)
			return result;
		
		@SuppressWarnings("unchecked")
		Pair<Long, T>[] xyArray
			= (Pair<Long, T>[]) xyData.toArray(new Pair[xyData.size()]);
		
		int windowStart = 0;
		int windowEnd = 0;
		// Don't compute average by first summing up all entries and then dividing, but by
		// continuously maintaining an already divided real average.
		// We must divide at every added item instead of only dividing after the last because the
		// values may be so large that they cause overflow or imprecision if we keep adding them up
		// until the end.
		double xAverage = 0;
		double yAverage = 0;
		do {
			int amount = windowEnd - windowStart;
			assert(amount >= 0);
			
			// Undo previous averaging
			xAverage *= amount;
			yAverage *= amount;
			
			// Put windowEnd into average
			xAverage += xyArray[windowEnd].x.doubleValue();
			yAverage += xyArray[windowEnd].y.doubleValue();
			++amount;
			xAverage /= amount;
			yAverage /= amount;
			
			// If the average contains enough measurements now then yield it
			if((xyArray[windowEnd].x - xyArray[windowStart].x) >= SECONDS.toMillis(seconds)
					&& amount >= 16) {
				
				assert(xAverage <= (xyArray[windowStart].x + SECONDS.toMillis(seconds))
					|| amount == 16);
				
				result.addLast(new Pair<>(round(xAverage), yAverage));
				
				// Remove windowStart from average in preparation of next iteration in order to
				// actually make this a moving average with a window of the given amount of seconds.
				// Do this here instead of at beginning of the loop so we don't need to check
				// whether we're eligible to do it.
				xAverage *= amount;
				yAverage *= amount;
				xAverage -= xyArray[windowStart].x.doubleValue();
				yAverage -= xyArray[windowStart].y.doubleValue();
				--amount;
				xAverage /= amount;
				yAverage /= amount;
				
				++windowStart;
			}
		} while(++windowEnd < xyArray.length);
		
		// If there is remaining data add it to the result if it contains at least the minimum
		// amount of measurements.
		// But if the result set is empty then ignore the minimum amount so we never return an
		// empty result.
		// FIXME: Does this still make sense with a moving average?
		if((windowEnd - windowStart) >= 16 || result.size() == 0)
			result.addLast(new Pair<>(round(xAverage), yAverage));
		
		return result;
	}

	/**
	 * Consumes a {@link LimitedArrayDeque} of {@link Pair}s where {@link Pair#x} is a
	 * {@link CurrentTimeUTC#getInMillis()} timestamp and {@link Pair#y}
	 * is an arbitrary {@link Number} which supports {@link Number#doubleValue()}.
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

	/**
	 * Returns a new {@link LimitedArrayDeque} with new {@link Pair} objects where each Pair's
	 * {@link Number#doubleValue()} of the {@link Pair#y} is multiplied by the given
	 * multiplier. */
	public static final <T extends Number> LimitedArrayDeque<Pair<Long, Double>> multiplyY(
			LimitedArrayDeque<Pair<Long, T>> xyData, long multiplier) {
		
		LimitedArrayDeque<Pair<Long, Double>> result
			= new LimitedArrayDeque<>(xyData.sizeLimit());
	
		for(Pair<Long, T> cur : xyData)
			result.addLast(new Pair<>(cur.x, cur.y.doubleValue() * multiplier));
		
		return result;
	}
}
