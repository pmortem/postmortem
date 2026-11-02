package app.postmortem.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartLabelTest {
    private val chartWidth = 400f
    private val labelWidth = 100f
    private val gap = 6f

    @Test
    fun sitsRightOfTheMarkerWhenThereIsRoom() {
        assertEquals(106f, labelX(100f, labelWidth, chartWidth, gap))
    }

    @Test
    fun flipsLeftOfTheMarkerNearTheRightEdge() {
        assertEquals(194f, labelX(300f, labelWidth, chartWidth, gap))
        assertEquals(294f, labelX(400f, labelWidth, chartWidth, gap))
    }

    @Test
    fun atTheLastPointTheLabelEndsBeforeTheEdge() {
        assertTrue(labelX(chartWidth, labelWidth, chartWidth, gap) + labelWidth <= chartWidth)
    }

    @Test
    fun atTheFirstPointTheLabelStartsInsideTheChart() {
        assertEquals(6f, labelX(0f, labelWidth, chartWidth, gap))
    }

    @Test
    fun aLabelWiderThanTheChartStartsAtZero() {
        assertEquals(0f, labelX(50f, 500f, chartWidth, gap))
    }
}
