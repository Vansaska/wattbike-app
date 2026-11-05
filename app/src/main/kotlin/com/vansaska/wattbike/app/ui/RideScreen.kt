package com.vansaska.wattbike.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vansaska.wattbike.app.LinkStatus
import com.vansaska.wattbike.app.MainViewModel
import com.vansaska.wattbike.core.Cue
import com.vansaska.wattbike.core.Durations
import com.vansaska.wattbike.core.RideState
import com.vansaska.wattbike.core.RouteOption
import com.vansaska.wattbike.core.kilojoules
import kotlin.math.abs

private val Green = Color(0xFF22C55E)
private val Amber = Color(0xFFEAB308)

/** Landscape ride screen: everything fits on one screen, no scrolling. */
@Composable
fun RideScreen(vm: MainViewModel) {
    val ride by vm.ride.collectAsState()
    val bike by vm.bike.state.collectAsState()
    val r = ride ?: return
    var confirmFinish by remember { mutableStateOf(false) }
    var routes by remember { mutableStateOf<List<RouteOption>?>(null) }

    // Keep the screen on and hide the status/navigation bars while riding.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler { confirmFinish = true }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---------- header ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                r.workoutName, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                "${Durations.format(r.elapsed)} / ${Durations.format(r.totalSeconds)}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(10.dp))
            val (txt, col) = when {
                bike.status != LinkStatus.CONNECTED -> "No bike" to Color(0xFFEF4444)
                !bike.hasControl -> "No control" to Accent
                r.inAmnesty -> "Fixed" to Color(0xFF3B82F6)
                else -> "ERG" to Green
            }
            Text(txt, color = col, style = MaterialTheme.typography.labelLarge)
        }

        // ---------- step + numbers ----------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.weight(1.15f)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val banner = when {
                        r.state == RideState.WAITING -> "Start pedalling to begin"
                        r.state == RideState.PAUSED -> "Paused — pedal to carry on"
                        r.planComplete -> "Workout complete — tap Finish"
                        else -> null
                    }
                    if (r.inAmnesty) {
                        Text("Warm-up: fixed resistance", style = MaterialTheme.typography.titleSmall, color = Color(0xFF3B82F6), maxLines = 1)
                        Text(Durations.format(r.amnestyLeft), fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
                        Text(banner ?: "Stop to stretch whenever you like",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            color = banner?.let { Accent } ?: MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(r.segmentLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (r.planComplete) "—" else Durations.format(r.segmentLeft), fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
                        val nextText = r.nextLabel?.let { n ->
                            "Next: $n${r.nextWatts?.let { " · $it W" } ?: ""}${r.nextCadence?.let { " · $it rpm" } ?: ""}"
                        }
                        Text(banner ?: nextText ?: " ", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = banner?.let { Accent } ?: MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (r.inAmnesty) {
                BigStat("Resistance", "${r.amnestyLevelPct}", "%", Modifier.weight(1f), Color(0xFF3B82F6))
            } else {
                BigStat("Target", "${r.targetWatts}", "W", Modifier.weight(1f), Accent)
            }
            val p = r.power
            val powerCol = when {
                p == null || r.inAmnesty -> MaterialTheme.colorScheme.onSurface
                abs(p - r.targetWatts) <= maxOf(10, r.targetWatts / 15) -> Green
                else -> Amber
            }
            BigStat("Power", p?.toString() ?: "–", "W", Modifier.weight(1f), powerCol)
            val tc = r.targetCadence
            val cadCol = when {
                tc == null || r.cadence == null -> MaterialTheme.colorScheme.onSurface
                abs(r.cadence - tc) <= 5 -> Green
                else -> Amber
            }
            BigStat(if (tc != null) "rpm · aim $tc" else "Cadence", r.cadence?.toString() ?: "–", "rpm", Modifier.weight(1f), cadCol)
        }

        // ---------- graph: takes all the spare height ----------
        ProfileGraph(r.segments, Modifier.fillMaxWidth().weight(1f), position = r.elapsed)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Energy ${r.energyPct}%", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.size(8.dp))
            LinearProgressIndicator(
                progress = { (r.energyPct / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f).height(6.dp),
                color = Accent,
            )
            Spacer(Modifier.size(8.dp))
            Text("${r.energyKj} / ${r.plannedKj} kJ · ${Durations.format(r.workoutLeft)} left",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---------- controls ----------
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { vm.adjustIntensity(-5) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Remove, "Easier") }
            Text(
                if (r.inAmnesty) "${r.amnestyLevelPct}% res." else "${r.intensity}%",
                style = MaterialTheme.typography.titleMedium,
            )
            FilledTonalIconButton(onClick = { vm.adjustIntensity(5) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Add, "Harder") }
            Spacer(Modifier.size(4.dp))
            if (r.inAmnesty) {
                Button(onClick = { vm.endAmnesty() }, modifier = Modifier.weight(1.6f).height(44.dp)) { Text("Start ERG now", maxLines = 1) }
            } else {
                Button(
                    onClick = { routes = vm.routeOptions() },
                    enabled = !r.planComplete,
                    modifier = Modifier.weight(1.6f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6), contentColor = Color.White),
                ) { Text("Different route", maxLines = 1) }
            }
            OutlinedButton(onClick = { vm.pauseRide() }, enabled = r.state == RideState.RUNNING, modifier = Modifier.weight(1f).height(44.dp)) { Text("Pause", maxLines = 1) }
            OutlinedButton(onClick = { confirmFinish = true }, modifier = Modifier.weight(1f).height(44.dp)) { Text("Finish", maxLines = 1) }
        }
    }

    // Full-screen flash at each change, readable out of the corner of your eye.
    val flash by vm.flash.collectAsState()
    flash?.let { cue ->
        val bg = when (cue) {
            Cue.SPEED_UP, Cue.LAST_ONE -> Color(0xFFF97316)
            Cue.EASE_OFF, Cue.COOL_DOWN -> Color(0xFF3B82F6)
            Cue.ERG_ON, Cue.DONE -> Color(0xFF22C55E)
        }
        Box(
            Modifier.fillMaxSize().background(bg.copy(alpha = 0.92f)).clickable { },
            contentAlignment = Alignment.Center,
        ) {
            Text(cue.phrase.uppercase(), fontSize = 64.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Finish ride?") },
            text = { Text(if (r.planComplete) "Nice work." else "${Durations.format(r.workoutLeft)} of the workout is left.") },
            confirmButton = { TextButton(onClick = { confirmFinish = false; vm.finishRide() }) { Text("Finish and save") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep riding") } },
        )
    }

    routes?.let { opts ->
        RouteDialog(
            options = opts,
            ftp = r.ftp,
            currentKj = remember(r.segments, r.elapsed) {
                com.vansaska.wattbike.core.Plan(r.segments).remainingFrom(r.elapsed).kilojoules(r.ftp)
            },
            onPick = { routes = null; vm.takeRoute(it) },
            onDismiss = { routes = null },
        )
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String, modifier: Modifier, colour: Color = MaterialTheme.colorScheme.onSurface) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = colour, lineHeight = 36.sp)
                Text(" $unit", modifier = Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RouteDialog(
    options: List<RouteOption>,
    ftp: Int,
    currentKj: Int,
    onPick: (RouteOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Take a different route") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (options.isEmpty()) Text("Less than a minute left, so just finish it off.")
                else Text("Same finish time, less work. Current plan: $currentKj kJ to go.", style = MaterialTheme.typography.bodySmall)
                for (o in options) {
                    Card(Modifier.fillMaxWidth().clickable { onPick(o) }) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Text(o.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text("${o.segments.kilojoules(ftp)} kJ", style = MaterialTheme.typography.labelLarge, color = Accent)
                            }
                            Text(o.description, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            ProfileGraph(o.segments, Modifier.fillMaxWidth().height(36.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay on course") } },
    )
}
