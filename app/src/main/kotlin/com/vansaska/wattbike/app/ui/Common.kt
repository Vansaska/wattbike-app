package com.vansaska.wattbike.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vansaska.wattbike.app.MainViewModel
import com.vansaska.wattbike.app.Screen
import com.vansaska.wattbike.core.Segment
import com.vansaska.wattbike.core.Zone

val Accent = Color(0xFFFFB020)

fun zoneColor(pct: Double): Color = when (Zone.of(pct)) {
    Zone.RECOVERY -> Color(0xFF8A929C)
    Zone.ENDURANCE -> Color(0xFF3B82F6)
    Zone.TEMPO -> Color(0xFF22C55E)
    Zone.SWEET_SPOT -> Color(0xFF9BD12F)
    Zone.THRESHOLD -> Color(0xFFEAB308)
    Zone.VO2MAX -> Color(0xFFF97316)
    Zone.ANAEROBIC -> Color(0xFFEF4444)
    Zone.SPRINT -> Color(0xFFA855F7)
}

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = Color(0xFF3B82F6),
    background = Color(0xFF0F1215),
    surface = Color(0xFF0F1215),
    surfaceVariant = Color(0xFF1B1F24),
    surfaceContainer = Color(0xFF1B1F24),
    surfaceContainerHigh = Color(0xFF22272D),
)

@Composable
fun AppRoot(vm: MainViewModel) {
    MaterialTheme(colorScheme = colors) {
        val stack by vm.stack.collectAsState()
        val message by vm.message.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(message) {
            message?.let {
                vm.messageShown()
                snackbar.showSnackbar(it)
            }
        }
        val top = stack.last()
        BackHandler(enabled = stack.size > 1 && top != Screen.Ride) { vm.back() }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (top) {
                    Screen.Home -> HomeScreen(vm)
                    is Screen.Detail -> DetailScreen(vm, top.workoutId)
                    is Screen.Edit -> EditScreen(vm, top.workoutId)
                    Screen.Ride -> RideScreen(vm)
                    is Screen.Summary -> SummaryScreen(vm, top.rideId)
                    Screen.History -> HistoryScreen(vm)
                    Screen.Settings -> SettingsScreen(vm)
                }
            }
        }
    }
}

/** Draws a workout's power profile, coloured by zone. Optionally marks the current position. */
@Composable
fun ProfileGraph(
    segments: List<Segment>,
    modifier: Modifier = Modifier,
    position: Int? = null,
    maxPct: Double = 130.0,
) {
    Canvas(modifier) {
        val total = segments.lastOrNull()?.end ?: return@Canvas
        if (total <= 0) return@Canvas
        val top = maxOf(maxPct, segments.maxOf { it.maxPct } + 5)
        fun x(t: Int) = t.toFloat() / total * size.width
        fun y(p: Double) = size.height - (p / top * size.height).toFloat()
        for (s in segments) {
            val path = Path().apply {
                moveTo(x(s.start), size.height)
                lineTo(x(s.start), y(s.fromPct))
                lineTo(x(s.end), y(s.toPct))
                lineTo(x(s.end), size.height)
                close()
            }
            val dim = position != null && s.end <= position
            drawPath(path, zoneColor(s.avgPct).copy(alpha = if (dim) 0.35f else 0.9f))
        }
        // FTP line
        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y(100.0)), Offset(size.width, y(100.0)), 1f)
        if (position != null) {
            val px = x(position.coerceIn(0, total))
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), 4f)
            drawCircle(Color.White, 6f, Offset(px, 6f), style = Stroke(3f))
        }
    }
}
