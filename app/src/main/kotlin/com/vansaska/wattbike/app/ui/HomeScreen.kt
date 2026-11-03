package com.vansaska.wattbike.app.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vansaska.wattbike.app.BikeConnection
import com.vansaska.wattbike.app.LinkStatus
import com.vansaska.wattbike.app.MainViewModel
import com.vansaska.wattbike.app.Screen
import com.vansaska.wattbike.app.bluetoothPermissions
import com.vansaska.wattbike.app.hasBluetoothPermissions
import com.vansaska.wattbike.core.Durations
import com.vansaska.wattbike.core.kilojoules
import com.vansaska.wattbike.core.toSegments

@Composable
fun HomeScreen(vm: MainViewModel) {
    val library by vm.library.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val ftp by vm.ftp.collectAsState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Workouts", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.sync() }, enabled = !syncing) {
                        if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Sync, "Sync workouts")
                    }
                    IconButton(onClick = { vm.go(Screen.History) }) { Icon(Icons.Default.History, "Ride history") }
                    IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                }
            }
            item { BikeCard(vm) }
            items(library, key = { it.workout.id }) { entry ->
                val w = entry.workout
                val segs = remember(w) { w.toSegments() }
                Card(Modifier.fillMaxWidth().clickable { vm.go(Screen.Detail(w.id)) }) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(w.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (entry.edited) Text("edited", color = Accent, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            "${Durations.format(w.totalSeconds)} · ~${segs.kilojoules(ftp)} kJ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        ProfileGraph(segs, Modifier.fillMaxWidth().height(44.dp))
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { vm.go(Screen.Edit(null)) },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("New workout") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
fun BikeCard(vm: MainViewModel) {
    val context = LocalContext.current
    val state by vm.bike.state.collectAsState()
    val data by vm.bike.data.collectAsState()
    var picking by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            vm.onPermissionsGranted()
            picking = true
            vm.bike.startScan()
        } else {
            vm.say("Bluetooth permission is needed to talk to the bike")
        }
    }

    fun beginPick() {
        if (!vm.bike.bluetoothEnabled) {
            context.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
            vm.say("Turn Bluetooth on, then come back")
            return
        }
        if (hasBluetoothPermissions(context)) {
            picking = true
            vm.bike.startScan()
        } else {
            permissionLauncher.launch(bluetoothPermissions)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            val (label, colour) = when (state.status) {
                LinkStatus.CONNECTED -> (if (state.hasControl) "Connected" else "Connected (waiting for control)") to Color(0xFF22C55E)
                LinkStatus.CONNECTING -> "Connecting…" to Accent
                LinkStatus.SCANNING -> "Searching…" to Accent
                LinkStatus.DISCONNECTED -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.deviceName ?: vm.prefs.lastDeviceName ?: "Bike", style = MaterialTheme.typography.titleMedium)
                    Text(label, color = colour, style = MaterialTheme.typography.bodySmall)
                }
                if (state.status == LinkStatus.CONNECTED) {
                    Text("${data.powerW ?: 0} W  ${data.cadenceRpm?.toInt() ?: 0} rpm", style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Accent) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.status) {
                    LinkStatus.CONNECTED, LinkStatus.CONNECTING ->
                        OutlinedButton(onClick = { vm.bike.disconnect() }) { Text("Disconnect") }
                    else -> {
                        val last = vm.prefs.lastDeviceAddress
                        if (last != null) {
                            Button(onClick = {
                                if (hasBluetoothPermissions(context)) vm.bike.connect(last)
                                else permissionLauncher.launch(bluetoothPermissions)
                            }) { Text("Connect") }
                        }
                        OutlinedButton(onClick = { beginPick() }) { Text(if (last == null) "Find bike" else "Choose another") }
                    }
                }
            }
        }
    }

    if (picking) DevicePicker(vm.bike, onPick = { addr, name -> picking = false; vm.connectBike(addr, name) }, onDismiss = { picking = false; vm.bike.stopScan() })
}

@Composable
private fun DevicePicker(bike: BikeConnection, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val found by bike.found.collectAsState()
    val state by bike.state.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose your bike") },
        text = {
            Column {
                Text(
                    "Pedal for a few seconds to wake the bike. Close the Wattbike Hub app so it doesn't take control.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                if (found.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.status == LinkStatus.SCANNING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.status == LinkStatus.SCANNING) "Searching…" else "Nothing found")
                    }
                }
                for (d in found) {
                    TextButton(onClick = { onPick(d.address, d.name) }, modifier = Modifier.fillMaxWidth()) {
                        Text(d.name, modifier = Modifier.weight(1f))
                        Text("${d.rssi} dBm", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { bike.startScan() }) { Text("Search again") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
