package de.tudarmstadt.physics.trackingplot.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import de.tudarmstadt.physics.trackingplot.geometry.RulerScale
import kotlin.math.max

/** One measured position in the XY trajectory. */
data class TrajectoryPoint(val x: Float, val y: Float)

/**
 * Chart data of one experiment: for every tracker an "x" and a "y" series over time,
 * plus the measured positions used by the XY trajectory view.
 *
 * MPAndroidChart is used only for the time plots. XY positions are kept in insertion order
 * and rendered by Compose, because a trajectory's x values are not necessarily sorted.
 * Values are stored in real-world units when a [RulerScale] is available, otherwise as
 * normalised frame coordinates. Changes are published through [version].
 */
class ChartModel(private val rulerScale: RulerScale?) {

    /** Unit of the plotted values, or null for normalised coordinates. */
    val unitLabel: String? = rulerScale?.unit?.symbol

    /** Time-series data displayed by MPAndroidChart. */
    val data = LineData()

    /** Incremented on every committed change; the chart view re-reads the data when it changes. */
    var version by mutableIntStateOf(0)
        private set

    /** Time of the newest sample in milliseconds since the experiment start. */
    var latestTimeMs by mutableFloatStateOf(0f)
        private set

    private val _hiddenSeries = mutableStateSetOf<String>()
    val hiddenSeries: Set<String> get() = _hiddenSeries

    private val seriesByTracker = HashMap<Int, Pair<LineDataSet, LineDataSet>>()
    private val trajectoryByTracker = LinkedHashMap<Int, MutableList<TrajectoryPoint>>()
    private var dirty = false
    private var pendingLatestTimeMs = 0f

    /** Tracker ids that have at least one sample, in ascending order. */
    val trackerIds: List<Int> get() = seriesByTracker.keys.sorted()

    /** Returns trajectory points in measurement order (not sorted by x). */
    fun trajectoryPoints(trackerId: Int): List<TrajectoryPoint> =
        trajectoryByTracker[trackerId].orEmpty()

    /** Adds a sample; it becomes visible (and [latestTimeMs] updates) on the next [commit]. */
    fun addSample(trackerId: Int, timeMs: Long, normalizedX: Double, normalizedY: Double) {
        val (seriesX, seriesY) = series(trackerId)
        val x = rulerScale?.x(normalizedX) ?: normalizedX
        val y = rulerScale?.y(normalizedY) ?: normalizedY
        val time = timeMs.toFloat()
        val positionX = x.toFloat()
        val positionY = y.toFloat()
        seriesX.addEntry(Entry(time, positionX))
        seriesY.addEntry(Entry(time, positionY))
        trajectoryByTracker.getOrPut(trackerId) { ArrayList() }
            .add(TrajectoryPoint(positionX, positionY))
        pendingLatestTimeMs = max(pendingLatestTimeMs, time)
        dirty = true
    }

    /** Publishes pending samples. Returns true when something changed. */
    fun commitIfDirty(): Boolean {
        if (!dirty) return false
        commit()
        return true
    }

    fun commit() {
        dirty = false
        data.notifyDataChanged()
        latestTimeMs = pendingLatestTimeMs
        version++
    }

    fun isSeriesVisible(label: String): Boolean = label !in _hiddenSeries

    /** A trajectory remains visible while at least one of its x/y series is enabled. */
    fun isTrajectoryVisible(trackerId: Int): Boolean =
        xLabel(trackerId) !in _hiddenSeries || yLabel(trackerId) !in _hiddenSeries

    fun toggleSeries(label: String) {
        val visible = label in _hiddenSeries
        if (visible) _hiddenSeries.remove(label) else _hiddenSeries.add(label)
        (data.getDataSetByLabel(label, false) as? LineDataSet)?.isVisible = visible
        commit()
    }

    private fun series(trackerId: Int): Pair<LineDataSet, LineDataSet> =
        seriesByTracker.getOrPut(trackerId) {
            val color = TrackerColors.color(trackerId).toArgb()
            val seriesX = newSeries(xLabel(trackerId), color, dashed = false)
            val seriesY = newSeries(yLabel(trackerId), color, dashed = true)
            data.addDataSet(seriesX)
            data.addDataSet(seriesY)
            seriesX to seriesY
        }

    private fun newSeries(label: String, color: Int, dashed: Boolean): LineDataSet =
        LineDataSet(ArrayList(), label).apply {
            this.color = color
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
            form = Legend.LegendForm.LINE
            if (dashed) {
                enableDashedLine(12f, 8f, 0f)
                setFormLineDashEffect(android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f))
            }
            isVisible = label !in _hiddenSeries
        }

    companion object {
        fun xLabel(trackerId: Int): String = "${TrackerColors.name(trackerId)} x"
        fun yLabel(trackerId: Int): String = "${TrackerColors.name(trackerId)} y"
    }
}
