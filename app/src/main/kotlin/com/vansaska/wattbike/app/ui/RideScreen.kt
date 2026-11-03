package com.vansaska.wattbike.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vansaska.wattbike.app.LinkStatus
import com.vansaska.wattbike.app.MainViewModel
import com.vansaska.wattbike.core.Durations
import com.vansaska.wattbike.core.RideState
import com.vansaska.wattbike.core.RouteOption
import com.vansaska.wattbike.core.kilojoules
import kotlin.math.abs

@Composable
fun RideScreen(vm: MainViewModel) {
    val ride by vm.ride.collectAsState()
    val bike by vm.bike.state.collectAsState()
    val r = ride ?: return
    var confirmFinish by remember { mutableStateOf(false) }
    var routes by remember { mutableStateOf<List<RouteOption>?>(null) }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { confirmFinish = true }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.workoutName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${Durations.format(r.elapsed)} / ${Durations.format(r.totalSeconds)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val (txt, col) = when {
                bike.status != LinkStatus.CONNECTED -> "Bike not connected" to Color(0xFFEF4444)
                !bike.hasControl -> "No ERG control" to Accent
                else -> "ERG on" to Color(0xFF22C55E)
            }
            Text(txt, color = col, style = MaterialTheme.typography.labelLarge)
        }

        val banner = when {
            r.state == RideState.WAITING -> "Start pedalling to begin"
            r.state == RideState.PAUSED -> "Paused — pedal to carry on"
            r.planComplete -> "Workout complete. Spin easy, then tap Finish."
            else -> null
        }
        banner?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(it, Modifier.fillMaxWidth().padding(10.dp), textAlign = TextAlign.Center, color = Accent)
            }
        }

        // Step and countdown
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(r.segmentLabel, style = MaterialTheme.typography.titleLarge)
                if (!r.planComplete) {
                    Text(Durations.format(r.segmentLeft), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                }
                r.nextLabel?.let { n ->
                    Text("Next: $n${r.nextWatts?.let { " · $it W" } ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Target vs actual
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigStat("Target", "${r.targetWatts}", "W", Modifier.weight(1f), Accent)
            val p = r.power
            val col = when {
                p == null -> MaterialTheme.colorScheme.onSurface
                abs(p - r.targetWatts) <= maxOf(10, r.targetWatts / 15) -> Color(0xFF22C55E)
                else -> Color(0xFFEAB308)
            }
            BigStat("Power", p?.toString() ?: "–", "W", Modifier.weight(1f), col)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigStat("Cadence", r.cadence?.toString() ?: "–", "rpm", Modifier.weight(1f))
            BigStat("Heart rate", r.heartRate?.toString() ?: "–", "bpm", Modifier.weight(1f))
        }

        ProfileGraph(r.segments, Modifier.fillMaxWidth().height(80.dp), position = r.elapsed)
        Text("${Durations.format(r.workoutLeft)} left", color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Intensity
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            FilledTonalIconButton(onClick = { vm.adjustIntensity(-5) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Remove, "Easier") }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 20.dp)) {
                Text("${r.intensity}%", style = MaterialTheme.typography.headlineSmall)
                Text("intensity", style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalIconButton(onClick = { vm.adjustIntensity(5) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Add, "Harder") }
        }

        Button(
            onClick = { routes = vm.routeOptions() },
            enabled = !r.planComplete,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6), contentColor = Color.White),
        ) { Text("Take a different route", style = MaterialTheme.typography.titleMedium) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { vm.pauseRide() }, enabled = r.state == RideState.RUNNING, modifier = Modifier.weight(1f)) { Text("Pause") }
            OutlinedButton(onClick = { confirmFinish = true }, modifier = Modifier.weight(1f)) { Text("Finish") }
        }
        Spacer(Modifier.height(24.dp))
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
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = colour)
                Text(" $unit", modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
