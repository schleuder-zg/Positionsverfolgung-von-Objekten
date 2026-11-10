package de.tudarmstadt.physics.trackingplot.ui.common

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.formatter.ValueFormatter
import java.util.Locale
import kotlin.math.max

/**
 * Line chart of a [ChartModel] with a row of chips to show/hide individual series.
 *
 * @param axisWindow x-axis range (milliseconds) to display, or null to fit all data.
 */
@Composable
fun TrackingChart(
    model: ChartModel,
    modifier: Modifier = Modifier,
    axisWindow: ClosedFloatingPointRange<Float>? = null,
    trackerCount: Int = model.trackerIds.size
) {
    var showTrajectory by rememberSaveable { mutableStateOf(false) }
    var invertY by rememberSaveable { mutableStateOf(true) }
    Column(modifier = modifier) {
        if (showTrajectory) {
            TrajectoryChartView(
                model = model,
                invertY = invertY,
                modifier = Modifier.fillMaxWidth().weight(1f).aspectRatio(1f)
            )
        } else {
            LineChartView(
                model = model,
                axisWindow = axisWindow,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(selected = !showTrajectory, onClick = { showTrajectory = false }, label = { Text("t-x / t-y") })
            FilterChip(selected = showTrajectory, onClick = { showTrajectory = true }, label = { Text("x-y-Bahn") })
            if (showTrajectory) {
                FilterChip(selected = invertY, onClick = { invertY = !invertY }, label = { Text(if (invertY) "y nach oben" else "y nach unten") })
            }
        }
        SeriesChips(model = model, trackerCount = trackerCount)
    }
}

/**
 * XY plot rendered directly on a Compose Canvas. This deliberately draws individual samples
 * and connects consecutive samples with straight segments. It does not sort x coordinates or use
 * MPAndroidChart's LineChartRenderer.
 */
@Composable
private fun TrajectoryChartView(
    model: ChartModel,
    invertY: Boolean,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val backgroundColor = MaterialTheme.colorScheme.surface
    val unitLabel = model.unitLabel
    val version = model.version

    Canvas(modifier = modifier) {
        val left = 64f
        val right = size.width - 16f
        val top = 16f
        val bottom = size.height - 52f
        val plotWidth = (right - left).coerceAtLeast(1f)
        val plotHeight = (bottom - top).coerceAtLeast(1f)

        val pointsByTracker = model.trackerIds
            .filter { model.isTrajectoryVisible(it) }
            .associateWith { model.trajectoryPoints(it) }
        val allPoints = pointsByTracker.values.flatten()

        if (allPoints.isEmpty()) return@Canvas

        // Use the same range on both axes so that the trajectory keeps its geometric proportions.
        val minValue = minOf(
            allPoints.minOf { minOf(it.x, it.y) },
            0f.takeIf { allPoints.size == 1 } ?: Float.POSITIVE_INFINITY
        )
        val maxValue = maxOf(
            allPoints.maxOf { maxOf(it.x, it.y) },
            0f.takeIf { allPoints.size == 1 } ?: Float.NEGATIVE_INFINITY
        )
        val span = (maxValue - minValue).coerceAtLeast(0.01f)
        val padding = span * 0.08f
        val axisMin = minValue - padding
        val axisMax = maxValue + padding
        val axisSpan = (axisMax - axisMin).coerceAtLeast(0.01f)

        // Grid and tick labels.
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor.toArgb()
            textSize = 27f
            textAlign = Paint.Align.RIGHT
        }
        val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor.toArgb()
            textSize = 27f
            textAlign = Paint.Align.CENTER
        }
        for (tick in 0..4) {
            val fraction = tick / 4f
            val x = left + fraction * plotWidth
            val y = bottom - fraction * plotHeight
            drawLine(gridColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
            drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            val value = axisMin + fraction * axisSpan
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.getDefault(), "%.2f", value),
                left - 8f, y + 9f, labelPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.getDefault(), "%.2f", value),
                x, bottom + 29f, xLabelPaint
            )
        }

        // Connect each sample to the next one in acquisition order with a straight segment.
        // Do not sort by x: the tracked object may move backwards or change direction.
        pointsByTracker.forEach { (trackerId, points) ->
            val color = TrackerColors.color(trackerId)
            for (index in 0 until points.lastIndex) {
                val first = points[index]
                val second = points[index + 1]
                val startX = left + ((first.x - axisMin) / axisSpan) * plotWidth
                val startYFraction = ((first.y - axisMin) / axisSpan).coerceIn(0f, 1f)
                val startY = if (invertY) {
                    bottom - startYFraction * plotHeight
                } else {
                    top + startYFraction * plotHeight
                }
                val endX = left + ((second.x - axisMin) / axisSpan) * plotWidth
                val endYFraction = ((second.y - axisMin) / axisSpan).coerceIn(0f, 1f)
                val endY = if (invertY) {
                    bottom - endYFraction * plotHeight
                } else {
                    top + endYFraction * plotHeight
                }
                drawLine(
                    color = color,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 2f
                )
            }

            // Draw the sample markers over the line segments.
            points.forEach { point ->
                val px = left + ((point.x - axisMin) / axisSpan) * plotWidth
                val yFraction = ((point.y - axisMin) / axisSpan).coerceIn(0f, 1f)
                val py = if (invertY) bottom - yFraction * plotHeight else top + yFraction * plotHeight
                drawCircle(color = color, radius = 4.5f, center = Offset(px, py))
            }
        }

        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor.toArgb()
            textSize = 25f
            textAlign = Paint.Align.CENTER
        }
        val suffix = unitLabel?.let { " ($it)" }.orEmpty()
        drawContext.canvas.nativeCanvas.drawText("x$suffix", (left + right) / 2f, size.height - 5f, axisPaint)
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.rotate(-90f, 18f, (top + bottom) / 2f)
        drawContext.canvas.nativeCanvas.drawText("y$suffix", 18f, (top + bottom) / 2f, axisPaint)
        drawContext.canvas.nativeCanvas.restore()
    }
}

@Composable
private fun LineChartView(
    model: ChartModel,
    axisWindow: ClosedFloatingPointRange<Float>?,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val textColor = colorScheme.onSurface.toArgb()
    val gridColor = colorScheme.outlineVariant.toArgb()
    val unitLabel = model.unitLabel

    AndroidView(
        modifier = modifier,
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                axisRight.isEnabled = false
                setNoDataText("")
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleEnabled(true)
                setPinchZoom(false)
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.valueFormatter = TimeAxisFormatter()
                axisLeft.valueFormatter = ValueAxisFormatter(unitLabel)
                legend.isWordWrapEnabled = true
            }
        },
        update = { chart ->
            // Reading the version here re-runs this block whenever the model commits.
            val version = model.version
            if (chart.data !== model.data) {
                chart.data = model.data
            }
            chart.xAxis.textColor = textColor
            chart.xAxis.gridColor = gridColor
            chart.xAxis.axisLineColor = gridColor
            chart.axisLeft.textColor = textColor
            chart.axisLeft.gridColor = gridColor
            chart.axisLeft.axisLineColor = gridColor
            chart.legend.textColor = textColor

            if (axisWindow != null) {
                chart.xAxis.axisMinimum = axisWindow.start
                chart.xAxis.axisMaximum = axisWindow.endInclusive
            } else {
                chart.xAxis.resetAxisMinimum()
                chart.xAxis.resetAxisMaximum()
            }
            chart.tag = version
            chart.notifyDataSetChanged()
            chart.invalidate()
        }
    )
}

@Composable
private fun SeriesChips(model: ChartModel, trackerCount: Int) {
    val ids = if (trackerCount > 0) (0 until trackerCount).toList() else model.trackerIds
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (trackerId in ids) {
            SeriesChip(model, ChartModel.xLabel(trackerId), trackerId)
            SeriesChip(model, ChartModel.yLabel(trackerId), trackerId)
        }
    }
}

@Composable
private fun SeriesChip(model: ChartModel, label: String, trackerId: Int) {
    val selected = model.isSeriesVisible(label)
    val trackerColor = TrackerColors.color(trackerId)
    FilterChip(
        selected = selected,
        onClick = { model.toggleSeries(label) },
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = trackerColor.copy(alpha = 0.25f),
            selectedLabelColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/** Formats milliseconds as m:ss or h:mm:ss. */
private class TimeAxisFormatter : ValueFormatter() {
    override fun getAxisLabel(value: Float, axis: AxisBase?): String {
        val totalSeconds = (value / 1000f).toLong()
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }
}

private class ValueAxisFormatter(private val unitLabel: String?) : ValueFormatter() {
    override fun getAxisLabel(value: Float, axis: AxisBase?): String =
        if (unitLabel == null) {
            String.format(Locale.getDefault(), "%.3f", value)
        } else {
            String.format(Locale.getDefault(), "%.2f %s", value, unitLabel)
        }
}
