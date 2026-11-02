package app.postmortem.ui

/**
 * Where the left edge of a label [labelWidth] wide goes, next to the marker at [markerX] on a chart [chartWidth] wide: to the
 * right of the marker, or to its left when the right side would be cut off, and never outside the chart.
 */
internal fun labelX(
    markerX: Float,
    labelWidth: Float,
    chartWidth: Float,
    gap: Float,
): Float {
    val right = markerX + gap
    val x = if (right + labelWidth <= chartWidth) right else markerX - gap - labelWidth
    return x.coerceIn(0f, (chartWidth - labelWidth).coerceAtLeast(0f))
}
