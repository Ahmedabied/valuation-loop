package uk.valuationloop.evidence;

import java.util.List;

/**
 * "The range implied by the comparables this agent attached": lower quartile, median and upper quartile of the
 * attached comparables' sale prices, rounded to whole pounds. Never called a band, never a valuation.
 *
 * Quartile method: linear interpolation between order statistics at position p * (n - 1) of the sorted prices
 * (the "inclusive" method; numpy.percentile's default and Excel's QUARTILE.INC). With fewer than 3 comparables
 * there is no meaningful spread, so {@link #of} returns null and the pack says why instead of showing a range.
 */
public record ComparablesRange(long low, long mid, long high) {

    public static final int MINIMUM_COMPARABLES = 3;

    public static final String METHOD = "lower quartile / median / upper quartile of attached comparables";

    /** null when there are fewer than {@value #MINIMUM_COMPARABLES} prices. */
    public static ComparablesRange of(List<Long> prices) {
        if (prices.size() < MINIMUM_COMPARABLES) {
            return null;
        }
        double[] sorted = prices.stream().mapToDouble(Long::doubleValue).sorted().toArray();
        return new ComparablesRange(
                Math.round(quantile(sorted, 0.25)),
                Math.round(quantile(sorted, 0.50)),
                Math.round(quantile(sorted, 0.75)));
    }

    /** Linear interpolation at position p * (n - 1); sorted must be ascending and non-empty. */
    public static double quantile(double[] sorted, double p) {
        double pos = p * (sorted.length - 1);
        int below = (int) Math.floor(pos);
        int above = (int) Math.ceil(pos);
        return sorted[below] + (pos - below) * (sorted[above] - sorted[below]);
    }

    /** (high - low) / mid: the spread as a share of the middle. 0.20 = the middle half spans 20% of the median. */
    public double relativeWidth() {
        return (double) (high - low) / mid;
    }
}
