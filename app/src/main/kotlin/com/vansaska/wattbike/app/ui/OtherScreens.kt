package com.vansaska.wattbike.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vansaska.wattbike.app.BuildConfig
import com.vansaska.wattbike.app.MainViewModel
import com.vansaska.wattbike.app.Prefs
import com.vansaska.wattbike.app.Screen
import com.vansaska.wattbike.app.StravaClient
import com.vansaska.wattbike.core.Durations
import com.vansaska.wattbike.core.RideRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun dateText(ms: Long) = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
fun SummaryScreen(vm: MainViewModel, id: String) {
    val rides by vm.rides.collectAsState()
    val uploading by vm.uploading.collectAsState()
    val r = rides.firstOrNull { it.id == id } ?: return
    val context = LocalContext.current
    var title by remember(id) { mutableStateOf(r.displayTitle) }
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BackHeader("Ride", { vm.back() }) }
        item {
            Text(dateText(r.startedAtMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                title, { title = it }, label = { Text("Title on Strava") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (title != r.displayTitle) TextButton(onClick = { vm.renameRide(id, title) }) { Text("Save title") }
        }
        item { StatsGrid(r) }
        item {
            when {
                r.stravaActivityId != null -> {
                    Text("On Strava", color = Color(0xFF22C55E))
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/activities/${r.stravaActivityId}")))
                    }) { Text("Open in Strava") }
                }
                id in uploading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Uploading to Strava…")
                }
                else -> {
                    r.uploadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = {
                        if (title != r.displayTitle) vm.renameRide(id, title)
                        vm.upload(id)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Upload to Strava") }
                }
            }
        }
        if (r.events.isNotEmpty()) {
            item {
                Text("During the ride", style = MaterialTheme.typography.titleSmall)
                for (e in r.events) Text("${Durations.format(e.atSecond)}  ${e.text}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.back() }) { Text("Done") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete ride", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ride?") },
            text = { Text(if (r.stravaActivityId != null) "It stays on Strava; this only removes it from the phone." else "It hasn't been uploaded, so it will be gone for good.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.back(); vm.deleteRide(id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatsGrid(r: RideRecord) {
    val rows = listOf(
        "Time" to Durations.format(r.activeSeconds),
        "Avg power" to "${r.avgPower} W",
        "Normalised" to "${r.normalizedPower} W",
        "Max (5 s)" to "${r.maxPower} W",
        "Work" to "${r.workKj.roundToInt()} kJ",
        "TSS" to "${r.tss}",
        "Cadence" to "${r.avgCadence} rpm",
        "Heart rate" to (r.avgHeartRate?.let { "$it bpm" } ?: "–"),
        "Distance" to "%.1f km".format(r.distanceM / 1000),
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            rows.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    for ((k, v) in pair) {
                        Column(Modifier.weight(1f)) {
                            Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val rides by vm.rides.collectAsState()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackHeader("Ride history", { vm.back() }) }
        if (rides.isEmpty()) item { Text("No rides yet.") }
        items(rides, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth().clickable { vm.go(Screen.Summary(r.id)) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.displayTitle, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${dateText(r.startedAtMs)} · ${Durations.format(r.activeSeconds)} · ${r.avgPower} W",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        r.stravaActivityId != null -> Text("Strava ✓", color = Color(0xFF22C55E), style = MaterialTheme.typography.labelMedium)
                        r.uploadError != null -> Text("Not uploaded", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        else -> Text("Not uploaded", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val ftpNow by vm.ftp.collectAsState()
    val stravaStatus by vm.stravaStatus.collectAsState()
    var ftp by remember { mutableStateOf(ftpNow.toString()) }
    var url by remember { mutableStateOf(vm.prefs.workoutsUrl) }
    var clientId by remember { mutableStateOf(vm.prefs.stravaClientId) }
    var secret by remember { mutableStateOf(vm.prefs.stravaClientSecret) }
    var autoUpload by remember { mutableStateOf(vm.prefs.autoUpload) }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BackHeader("Settings", { vm.back() }) }
        item {
            Text("Fitness", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    ftp, { ftp = it.filter(Char::isDigit) }, label = { Text("FTP (watts)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val v = ftp.toIntOrNull()
                    if (v != null && v in 50..600) { vm.setFtp(v); vm.say("FTP set to $v W") } else vm.say("FTP should be between 50 and 600")
                }) { Text("Save") }
            }
        }
        item { HorizontalDivider() }
        item {
            Text("Riding", style = MaterialTheme.typography.titleSmall)
            var amnesty by remember { mutableStateOf((vm.prefs.amnestySeconds / 60.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }) }
            var voice by remember { mutableStateOf(vm.prefs.voiceCues) }
            var chime by remember { mutableStateOf(vm.prefs.chimeCues) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    amnesty, { amnesty = it }, label = { Text("Warm-up amnesty (minutes)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val m = amnesty.replace(',', '.').toDoubleOrNull()
                    if (m != null && m in 0.0..10.0) { vm.prefs.amnestySeconds = (m * 60).roundToInt(); vm.say("Saved") }
                    else vm.say("Enter 0 to 10 minutes")
                }) { Text("Save") }
            }
            Text(
                "Fixed resistance at the start, so stopping to stretch doesn't make the bike surge. 0 turns it off. " +
                    "Change the level with −/+ during the warm-up; it's remembered.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Chime at each change", modifier = Modifier.weight(1f))
                Switch(chime, { chime = it; vm.prefs.chimeCues = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Spoken cue after the chime", modifier = Modifier.weight(1f))
                Switch(voice, { voice = it; vm.prefs.voiceCues = it })
            }
            Text("Test:", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((label, cue) in listOf(
                    "Speed up" to com.vansaska.wattbike.core.Cue.SPEED_UP,
                    "Ease off" to com.vansaska.wattbike.core.Cue.EASE_OFF,
                    "Last one" to com.vansaska.wattbike.core.Cue.LAST_ONE,
                    "Cool down" to com.vansaska.wattbike.core.Cue.COOL_DOWN,
                    "Done" to com.vansaska.wattbike.core.Cue.DONE,
                )) OutlinedButton(onClick = { vm.testCue(cue) }) { Text(label, maxLines = 1) }
            }
        }
        item { HorizontalDivider() }
        item {
            Text("Strava", style = MaterialTheme.typography.titleSmall)
            Text(stravaStatus, color = if (vm.strava.isConnected) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(
                "From strava.com/settings/api. Set \"Authorization Callback Domain\" to: localhost",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(secret, { secret = it }, label = { Text("Client Secret") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.prefs.stravaClientId = clientId
                    vm.prefs.stravaClientSecret = secret
                    vm.refreshStrava()
                    if (!vm.strava.isConfigured) { vm.say("Enter both the client ID and secret"); return@Button }
                    context.startActivity(Intent(Intent.ACTION_VIEW, vm.strava.authorizeUri()))
                }) { Text(if (vm.strava.isConnected) "Reconnect" else "Connect Strava") }
                if (vm.strava.isConnected) OutlinedButton(onClick = { vm.disconnectStrava() }) { Text("Disconnect") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Upload automatically when I finish", modifier = Modifier.weight(1f))
                Switch(autoUpload, { autoUpload = it; vm.prefs.autoUpload = it })
            }
            Text("Redirect address used: ${StravaClient.REDIRECT_URI}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { HorizontalDivider() }
        item {
            Text("Workout library", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(url, { url = it }, label = { Text("Workouts file address") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.prefs.workoutsUrl = url.trim(); vm.say("Saved") }) { Text("Save") }
                TextButton(onClick = { url = Prefs.DEFAULT_WORKOUTS_URL; vm.prefs.workoutsUrl = url }) { Text("Reset") }
            }
        }
        item { HorizontalDivider() }
        item {
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Vansaska/wattbike-app/releases/latest")))
            }) { Text("Check for a newer version") }
        }
    }
}
