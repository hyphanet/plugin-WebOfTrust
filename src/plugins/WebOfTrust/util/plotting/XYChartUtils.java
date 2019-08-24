package plugins.WebOfTrust.util.plotting;

import static java.lang.Double.isInfinite;
import static java.lang.Double.isNaN;
import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.knowm.xchart.style.Styler.LegendPosition.InsideNW;

import java.io.IOException;
import java.util.Iterator;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.markers.SeriesMarkers;

import freenet.l10n.BaseL10n;
import freenet.support.CurrentTimeUTC;
import plugins.WebOfTrust.ui.web.StatisticsPage;
import plugins.WebOfTrust.ui.web.StatisticsPage.StatisticsPlotRenderer;
import plugins.WebOfTrust.util.LimitedArrayDeque;
import plugins.WebOfTrust.util.Pair;

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
		
		String mLabel = null;

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
		
		public void setLabel(String label) {
			mLabel = label;
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
	@SafeVarargs
	public static final <T extends Number> byte[] getTimeBasedPlotPNG(
			BaseL10n l10n, String title, String xLabelHours,
			String xLabelMinutes, String yLabel, TimeChart<T>... timeCharts) {
		
		// If the amount of measurements we've gathered is at least 2 hours then we measure the
		// X-axis in hours, otherwise we measure it in minutes.
		// Using 2 hours instead of the more natural 1 hour because 1 hour measurements are a
		// typical benchmark of bootstrapping and I don't want to annoy people who want to measure
		// that with the X-axis not showing minutes.
		boolean hours = false;
		for(TimeChart<T> xyData : timeCharts) {
			hours |= SECONDS.toHours(
				(long)(xyData.peekLast().x - xyData.peekFirst().x)
			) >= 2;
		}
		
		// FIXME: Use large resolution and have the HTML scale it to the screen size
		XYChart c = new XYChart(600, 400);
		c.setTitle(l10n.getString(title));
		c.setXAxisTitle(l10n.getString(hours ? xLabelHours : xLabelMinutes));
		c.setYAxisTitle(l10n.getString(yLabel));
		c.getStyler().setLegendPosition(InsideNW);
		// Visibility of the labels to distinguish the multiple TimeCharts we render into the plot.
		c.getStyler().setLegendVisible(timeCharts.length > 1);
		
		for(TimeChart<T> xyData : timeCharts) {
		double timeUnit = (hours ? HOURS : MINUTES).toSeconds(1);
		double[] x = new double[xyData.size()];
		double[] y = new double[x.length];
		int i = 0;
		for(Pair<Double, T> p : xyData) {
			x[i] = p.x / timeUnit;
			y[i] = p.y.doubleValue();
			++i;
		}
		
		// The series label is not allowed to be empty so use the chart title if it is.
		XYSeries s = c.addSeries(xyData.mLabel != null ? xyData.mLabel : c.getTitle(), x, y);
		// For debugging use e.g. SeriesMarkers.CIRCLE
		s.setMarker(SeriesMarkers.NONE);
		}
		
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

	/** FIXME: Replace all usage of this with the varargs getTimeBasedPlotPNG() */
	public static final <T extends Number> byte[] getTimeBasedPlotPNG(
			TimeChart<T> timeChart, BaseL10n l10n, String title, String xLabelHours,
			String xLabelMinutes, String yLabel) {
		
		return getTimeBasedPlotPNG(l10n, title, xLabelHours, xLabelMinutes, yLabel, timeChart);
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
	 * - the current window size is large enough to span at least the given amount of seconds,
	 *   and small enough that removal of a single element would result in less contained
	 *   measurements than for the desired amount of seconds.
	 * - the window contains at least 16 Pairs of measurements. This additional requirement prevents
	 *   the plot from being jumpy in time areas where there have been few measurements.
	 * 
	 * The resulting dataset's {@link TimeChart#size()} will be less than or equal to the input's
	 * size minus 15 elements.
	 * Its {@link TimeChart#sizeLimit()} will be the same. */
	public static final <T extends Number> TimeChart<Double> movingAverage(
			TimeChart<T> chart, int seconds) {
		
		// System.out.println("movingAverage(chart, " + seconds + ")...");
		
		assert(seconds > 0);
		
		TimeChart<Double> result = new TimeChart<>(chart.sizeLimit());
		
		if(chart.size() < 16)
			return result;
		
		@SuppressWarnings("unchecked")
		Pair<Double, T>[] data
			= (Pair<Double, T>[]) chart.toArray(new Pair[chart.size()]);
		
		// int unyieldedAmount = 0; // Included in average but not yielded as output yet

		for(int windowEnd = 15; windowEnd < data.length; ++windowEnd) {
			int windowStart = windowEnd;
			// Don't compute average by first summing up all entries and then dividing, but by
			// continuously maintaining an already divided real average.
			// We must divide at every added item instead of only dividing after the last because
			// the values may be so large that they cause overflow or imprecision if we keep adding
			// them up until the end.
			double xAverage = data[windowEnd].x;
			double yAverage = data[windowEnd].y.doubleValue();
			int amount = 1;
			// Expand the window towards the beginning of the array until it is large enough
			// to cover the given minimum amount measurements and seconds.
			boolean enoughData;
			while((enoughData =
						(amount >= 16 && data[windowEnd].x - data[windowStart].x >= seconds))
					== false) {
				
				--windowStart;
				
				if(windowStart < 0) {
					enoughData = false;
					break;
				}
				
				xAverage *= amount;
				yAverage *= amount;
				xAverage += data[windowStart].x;
				yAverage += data[windowStart].y.doubleValue();
				++amount;
				xAverage /= amount;
				yAverage /= amount;
				
				assert(amount == windowEnd - windowStart + 1);
				assert(xAverage >= data[windowStart].x);
				assert(xAverage <= data[windowEnd].x);
			}
			
			// String logPrefix;
			if(enoughData) {
				result.addLast(new Pair<>(xAverage, yAverage));
				// logPrefix = "Yielded element " + result.size();
			} else {
				// ++unyieldedAmount;
				// logPrefix = "Not yielding element";
			}
			
			windowStart = max(windowStart, 0); // Prevent ArrayIndexOutOfBoundsException
			/*
			System.out.println(logPrefix
				+ " from: data[" + windowStart + "] to data[" + windowEnd + "]."
				+ " seconds = " + (data[windowEnd].x - data[windowStart].x)
				+ "; amount = " + amount + "; xAverage = " + xAverage + "; yAverage = " + yAverage);
			*/
		}
		
		// System.out.println("Total unyielded amount: " + unyieldedAmount);
		
		// Each output element must consist of at least 16 inputs so the first 15 inputs do not
		// cause output.
		assert(result.size() <= max(0, chart.size() - 15));
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
