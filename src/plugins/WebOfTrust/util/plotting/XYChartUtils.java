package plugins.WebOfTrust.util.plotting;

import static java.lang.Double.isInfinite;
import static java.lang.Double.isNaN;
import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.HOURS;
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
 * the Y-value is a measurement at the given time. */
public final class XYChartUtils {

	/**
	 * Stores X/Y-values suitable for preprocessing by {@link XYChartUtils}' functions and plotting
	 * by its {@link XYChartUtils#getTimeBasedPlotPNG(TimeChart, BaseL10n, String, String, String,
	 * String)}.
	 * 
	 * Is a {@link LimitedArrayDeque} of {@link Pair}s where the Pair's x-value is a
	 * {@link CurrentTimeUTC#getInMillis()} timestamp, converted to a double value of seconds; and
	 * the y-value is an arbitrary {@link Number} which supports {@link Number#doubleValue()} and is
	 * the subject of the chart to be plotted.
	 * 
	 * The conversion from milliseconds to seconds increases floating point precision of
	 * {@link XYChartUtils}'s preprocessing functions.
	 * This conversion is a valid thing to do here as the charts are typically intended to cover
	 * areas of minutes to hours and hence millisecond values are not interesting to the user. */
	public static final class TimeChart<T extends Number>
			extends LimitedArrayDeque<Pair<Double, T>> {

		public TimeChart(int sizeLimit) {
			super(sizeLimit);
		}

		/**
		 * @param data A queue where the x-value of the containing Pairs is a
		 *     {@link CurrentTimeUTC#getInMillis()} timestamp.
		 * @param t0 The {@link CurrentTimeUTC#getInMillis()} at the t=0 origin of the resulting
		 *     plot. By using this the time labels on the X-axis will not be absolute time but a
		 *     relative time offset, e.g. "3 minutes".
		 *     This value is subtracted from each x value of the data to move the origin of the
		 *     resulting chart to this point in time.
		 *     Typically you would set this to the startup time of WoT. */
		public TimeChart(LimitedArrayDeque<Pair<Long, T>> data, long t0) {
			super(data.sizeLimit());
			
			double oneSecondInMillis = SECONDS.toMillis(1);
			for(Pair<Long, T> p : data) {
				// Subtract t0 as long before converting to double for more floating point precision
				long x = p.x - t0;
				double xInSeconds = (double)x / oneSecondInMillis;
				addLast(new Pair<Double, T>(xInSeconds, p.y));
			}
		}
	}

	/**
	 * Generic implementation of creating an {@link XYChart} where the X-axis is the time.
	 * Can be used by {@link StatisticsPlotRenderer} implementations for their purposes.
	 * 
	 * @param xyData The plot data. ATTENTION: It MUST always contain at least one entry.
	 * @param l10n The {@link BaseL10n} used to translate the given string keys.
	 * @param title L10n key of the label on top of the plot.
	 * @param xLabelHours L10n key of the X-axis label if it is automatically chosen to display
	 *     hours.
	 * @param xLabelMinutes L10n key of the X-axis label if it is automatically chosen to display
	 *     minutes.
	 * @param yLabel L10n key of the Y-axis label.
	 * @return An image of the PNG format, serialized to a byte array. */
	public static final <T extends Number> byte[] getTimeBasedPlotPNG(
			TimeChart<T> xyData, BaseL10n l10n, String title, String xLabelHours,
			String xLabelMinutes, String yLabel) {
		
		// If the amount of measurements we've gathered is at least 2 hours then we measure the
		// X-axis in hours, otherwise we measure it in minutes.
		// Using 2 hours instead of the more natural 1 hour because 1 hour measurements are a
		// typical benchmark of bootstrapping and I don't want to annoy people who want to measure
		// that with the X-axis not showing minutes.
		boolean hours = SECONDS.toHours(
				(long)(xyData.peekLast().x - xyData.peekFirst().x)
			) >= 2;
		
		double timeUnit = (hours ? HOURS : MINUTES).toSeconds(1);
		double[] x = new double[xyData.size()];
		double[] y = new double[x.length];
		int i = 0;
		for(Pair<Double, T> p : xyData) {
			x[i] = p.x / timeUnit;
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
	 * - a {@link TimeChart} to be processed.
	 * - an integer threshold of seconds.
	 * 
	 * Output:
	 * A new TimeChart is returned where each contained Pair contains the moving average
	 * X- and Y-values of the Pairs in the source dataset across the given amount of seconds.
	 *
	 * This is computed by sliding a moving window across the dataset and yielding an output Pair
	 * for every input pair as long as:
	 * - the current window contains measurements which span at least the given amount of seconds.
	 * - the window contains at least 16 Pairs of measurements. This additional requirement prevents
	 *   the plot from being jumpy in time areas where there have been few measurements.
	 * 
	 * The resulting dataset's {@link TimeChart#size()} will be less than or equal to the input's
	 * size.
	 * Its {@link TimeChart#sizeLimit()} will be the same. */
	public static final <T extends Number> TimeChart<Double> movingAverage(
			TimeChart<T> xyData, int seconds) {
		
		// FIXME: Comment all logging in this function out once the bugs are fixed
		System.out.println("movingAverage(data, " + seconds + ")...");
		
		assert(seconds > 0);
		
		TimeChart<Double> result = new TimeChart<>(xyData.sizeLimit());
		
		if(xyData.size() == 0)
			return result;
		
		@SuppressWarnings("unchecked")
		Pair<Double, T>[] xyArray
			= (Pair<Double, T>[]) xyData.toArray(new Pair[xyData.size()]);
		
		int windowStart = 0;
		int windowEnd = 0;
		// Don't compute average by first summing up all entries and then dividing, but by
		// continuously maintaining an already divided real average.
		// We must divide at every added item instead of only dividing after the last because the
		// values may be so large that they cause overflow or imprecision if we keep adding them up
		// until the end.
		double xAverage = 0;
		double yAverage = 0;
		int unyieldedAmount = 0; // Included in average but not yielded as output yet
		do {
			int amount = windowEnd - windowStart;
			assert(amount >= 0);
			
			// Undo previous averaging
			xAverage *= amount;
			yAverage *= amount;
			
			// Put windowEnd into average
			xAverage += xyArray[windowEnd].x;
			yAverage += xyArray[windowEnd].y.doubleValue();
			++amount;
			xAverage /= amount;
			yAverage /= amount;
			
			// If the average contains enough measurements now then yield it
			if((xyArray[windowEnd].x - xyArray[windowStart].x) >= seconds
					&& amount >= 16) {
				
				assert(xAverage >= xyArray[windowStart].x);
				assert(xAverage <= xyArray[windowEnd].x);
				
				result.addLast(new Pair<>(xAverage, yAverage));
				System.out.println("Yielded element " + result.size()
					+ " from: xyArray[" + windowStart + "] to xyArray[" + windowEnd + "]."
					+ " seconds = " + (xyArray[windowEnd].x - xyArray[windowStart].x)
					+ "; amount = " + amount);
				
				// Remove windowStart from average in preparation of next iteration in order to
				// actually make this a moving average with a window of the given amount of seconds.
				// Do this here instead of at beginning of the loop so we don't need to check
				// whether we're eligible to do it.
				// FIXME: It'd be better to do it at the beginning to avoid one useless division
				// and multiplication by amount, that may be a waste of floating point accuracy.
				// Avoiding having the same if() at the loop beginning can also be achieved by
				// setting a "boolean shiftWindowStart" to true here and checking it at the
				// beginning.
				xAverage *= amount;
				yAverage *= amount;
				xAverage -= xyArray[windowStart].x;
				yAverage -= xyArray[windowStart].y.doubleValue();
				--amount;
				xAverage /= amount;
				yAverage /= amount;
				
				++windowStart;
				unyieldedAmount = 0;
			} else {
				System.out.println("Not yielding element"
					+ " from: xyArray[" + windowStart + "] to xyArray[" + windowEnd + "]."
					+ " seconds = " + (xyArray[windowEnd].x - xyArray[windowStart].x)
					+ "; amount = " + amount);
				
				++unyieldedAmount;
			}
		} while(++windowEnd < xyArray.length);
		
		System.out.println("Remaining unyielded amount: " + unyieldedAmount);
		
		// Each output element must consist of at least 16 inputs so the first 15 inputs do not
		// cause output.
		assert(result.size() <= max(0, xyData.size() - 15));
		return result;
	}

	/**
	 * Returns a new TimeChart which contains the dy/dx of the given plot data.
	 * 
	 * The resulting dataset's {@link TimeChart#size()} will be at most the size() of the input
	 * dataset minus 1.
	 * Its {@link TimeChart#sizeLimit()} will be the same. */
	public static final <T extends Number> TimeChart<Double> differentiate(TimeChart<T> xyData) {
		TimeChart<Double> result = new TimeChart<>(xyData.sizeLimit());
		
		if(xyData.size() < 2)
			return result;
		
		Iterator<Pair<Double, T>> i = xyData.iterator();
		Pair<Double, T> prev = i.next();
		do {
			Pair<Double, T> cur = i.next();
			
			double dx = cur.x - prev.x;
			// Avoid division by zero in dy/dx.
			if(abs(dx) <= Double.MIN_VALUE)
				continue;
			
			double dy = cur.y.doubleValue() - prev.y.doubleValue();
			
			double x = prev.x + dx/2;
			double y = dy / dx;
			
			assert(!isInfinite(y) && !isNaN(y)) : "Division by zero!";
			
			result.addLast(new Pair<>(x, y));
			prev = cur;
		} while(i.hasNext());
		
		assert(result.size() <= (xyData.size() - 1))
			: "The first input element is consumed without yielding an output.";
		return result;
	}

	/**
	 * Returns a new {@link TimeChart} where each Pair's {@link Number#doubleValue()} of the
	 * {@link Pair#y} is multiplied by the given multiplier. */
	public static final <T extends Number> TimeChart<Double> multiplyY(
			TimeChart<T> xyData, long multiplier) {
		
		TimeChart<Double> result = new TimeChart<>(xyData.sizeLimit());
		
		for(Pair<Double, T> cur : xyData)
			result.addLast(new Pair<>(cur.x, cur.y.doubleValue() * multiplier));
		
		return result;
	}
}
