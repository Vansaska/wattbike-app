package com.vansaska.wattbike.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vansaska.wattbike.app.MainViewModel
import com.vansaska.wattbike.app.Screen
import com.vansaska.wattbike.core.Durations
import com.vansaska.wattbike.core.Step
import com.vansaska.wattbike.core.StepKind
import com.vansaska.wattbike.core.Workout
import com.vansaska.wattbike.core.WorkoutJson
import com.vansaska.wattbike.core.kilojoules
import com.vansaska.wattbike.core.toSegments
import kotlin.math.roundToInt

@Composable
fun BackHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun DetailScreen(vm: MainViewModel, id: String) {
    val library by vm.library.collectAsState()
    val ftp by vm.ftp.collectAsState()
    val entry = library.firstOrNull { it.workout.id == id } ?: return
    val w = entry.workout
    val segs = remember(w) { w.toSegments() }
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        item { BackHeader(w.name, { vm.back() }) }
        item {
            Text(
                "${Durations.format(w.totalSeconds)} · ~${segs.kilojoules(ftp)} kJ at FTP $ftp W",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (w.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(w.description)
            }
            Spacer(Modifier.height(12.dp))
            ProfileGraph(segs, Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.startRide(w) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Start ride", style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.go(Screen.Edit(w.id)) }) { Text("Edit") }
                OutlinedButton(onClick = { vm.duplicate(w.id)?.let { vm.go(Screen.Edit(it)) } }) { Text("Copy") }
                OutlinedButton(onClick = {
                    val text = WorkoutJson.encodeToString(Workout.serializer(), w)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share workout"))
                }) { Text("Share") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
            }
            if (entry.edited) {
                Text(
                    "You've edited this on the phone, so syncing won't overwrite it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Accent,
                )
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
        }
        items(segs) { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Durations.format(s.start), fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s.label, modifier = Modifier.weight(1f), color = zoneColor(s.avgPct))
                Text(Durations.format(s.duration), modifier = Modifier.width(56.dp))
                val wFrom = (s.fromPct * ftp / 100).roundToInt()
                val wTo = (s.toPct * ftp / 100).roundToInt()
                Text(if (s.isRamp) "$wFrom→$wTo W" else "$wFrom W", modifier = Modifier.width(96.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${w.name}?") },
            text = {
                Text(if (entry.fromRepo) "It will come back next time you sync, unless it's also removed from the workouts file." else "This can't be undone.")
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteWorkout(w.id); vm.back() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------- editor ----------------

private var uidCounter = 0L

/** Editable form of a step: every field is text so half-typed values don't get lost. */
class EditNode(
    kind: StepKind,
    duration: String = "",
    power: String = "",
    from: String = "",
    to: String = "",
    times: String = "",
    label: String = "",
    children: List<EditNode> = emptyList(),
) {
    val uid = uidCounter++
    var kind by mutableStateOf(kind)
    var duration by mutableStateOf(duration)
    var power by mutableStateOf(power)
    var from by mutableStateOf(from)
    var to by mutableStateOf(to)
    var times by mutableStateOf(times)
    var label by mutableStateOf(label)
    val children = mutableStateListOf<EditNode>().apply { addAll(children) }

    fun toStep(where: String): Step {
        fun num(v: String, name: String): Int =
            v.trim().toIntOrNull() ?: throw IllegalArgumentException("$where: $name must be a number")
        fun dur(): Int {
            val d = runCatching { Durations.parse(duration) }.getOrNull()
                ?: throw IllegalArgumentException("$where: duration should look like 5:00")
            if (d <= 0) throw IllegalArgumentException("$where: duration missing")
            return d
        }
        val lbl = label.trim().ifEmpty { null }
        return when (kind) {
            StepKind.STEADY -> Step.steady(dur(), num(power, "power"), lbl)
            StepKind.RAMP -> Step.ramp(dur(), num(from, "start power"), num(to, "end power"), lbl)
            StepKind.REPEAT -> {
                if (children.isEmpty()) throw IllegalArgumentException("$where: repeat has no steps")
                Step(
                    times = num(times, "repeat count").coerceAtLeast(1),
                    steps = children.mapIndexed { i, c -> c.toStep("$where, part ${i + 1}") },
                    label = lbl,
                )
            }
        }
    }

    companion object {
        fun from(s: Step): EditNode = when (s.kind) {
            StepKind.STEADY -> EditNode(StepKind.STEADY, Durations.format(s.duration), power = (s.power ?: 50).toString(), label = s.label.orEmpty())
            StepKind.RAMP -> EditNode(
                StepKind.RAMP, Durations.format(s.duration),
                from = (s.from ?: s.power ?: 50).toString(), to = (s.to ?: s.power ?: 50).toString(), label = s.label.orEmpty(),
            )
            StepKind.REPEAT -> EditNode(
                StepKind.REPEAT, times = (s.times ?: 1).toString(), label = s.label.orEmpty(),
                children = (s.steps ?: emptyList()).map { from(it) },
            )
        }

        fun newSteady() = EditNode(StepKind.STEADY, "5:00", power = "75")
        fun newRepeat() = EditNode(
            StepKind.REPEAT, times = "4",
            children = listOf(EditNode(StepKind.STEADY, "3:00", power = "105"), EditNode(StepKind.STEADY, "3:00", power = "50")),
        )
    }
}

@Composable
fun EditScreen(vm: MainViewModel, id: String?) {
    val original = remember(id) { id?.let { vm.workout(it)?.workout } ?: vm.newWorkoutTemplate() }
    var name by remember { mutableStateOf(original.name) }
    var description by remember { mutableStateOf(original.description) }
    val nodes = remember { mutableStateListOf<EditNode>().apply { addAll(original.steps.map { EditNode.from(it) }) } }
    var jsonMode by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    val ftp by vm.ftp.collectAsState()

    fun build(): Result<Workout> = runCatching {
        if (name.isBlank()) throw IllegalArgumentException("Give it a name")
        if (nodes.isEmpty()) throw IllegalArgumentException("Add at least one step")
        Workout(original.id, name.trim(), description.trim(), nodes.mapIndexed { i, n -> n.toStep("Step ${i + 1}") })
    }

    val built = build()

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            BackHeader(if (id == null) "New workout" else "Edit workout", { vm.back() }) {
                TextButton(onClick = {
                    build().onSuccess { vm.saveWorkout(it); vm.back(); vm.say("Saved") }
                        .onFailure { vm.say(it.message ?: "Can't save") }
                }) { Text("Save") }
            }
        }
        item {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
            OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
        }
        item {
            built.onSuccess { w ->
                val segs = w.toSegments()
                Text("${Durations.format(w.totalSeconds)} · ~${segs.kilojoules(ftp)} kJ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProfileGraph(segs, Modifier.fillMaxWidth().height(70.dp))
            }.onFailure {
                Text(it.message ?: "", color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Power is % of FTP. Durations like 5:00.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (!jsonMode) {
                        jsonText = WorkoutJson.encodeToString(Workout.serializer(), built.getOrDefault(original))
                        jsonMode = true
                    } else {
                        jsonMode = false
                    }
                }) { Text(if (jsonMode) "Back to form" else "Edit as text") }
            }
        }
        if (jsonMode) {
            item {
                OutlinedTextField(
                    jsonText, { jsonText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Button(onClick = {
                    runCatching { WorkoutJson.decodeFromString(Workout.serializer(), jsonText) }
                        .onSuccess { w ->
                            name = w.name
                            description = w.description
                            nodes.clear()
                            nodes.addAll(w.steps.map { EditNode.from(it) })
                            jsonMode = false
                        }
                        .onFailure { vm.say("Couldn't read that: ${it.message?.take(120)}") }
                }) { Text("Apply") }
            }
        } else {
            items(nodes, key = { it.uid }) { node ->
                val index = nodes.indexOf(node)
                StepCard(
                    node = node,
                    title = "Step ${index + 1}",
                    onUp = if (index > 0) ({ nodes.removeAt(index); nodes.add(index - 1, node) }) else null,
                    onDown = if (index < nodes.lastIndex) ({ nodes.removeAt(index); nodes.add(index + 1, node) }) else null,
                    onDelete = { nodes.remove(node) },
                    allowRepeat = true,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nodes.add(EditNode.newSteady()) }) { Text("+ Step") }
                    OutlinedButton(onClick = { nodes.add(EditNode.newRepeat()) }) { Text("+ Intervals") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepCard(
    node: EditNode,
    title: String,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onDelete: () -> Unit,
    allowRepeat: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                onUp?.let { IconButton(onClick = it) { Icon(Icons.Default.ArrowUpward, "Move up") } }
                onDown?.let { IconButton(onClick = it) { Icon(Icons.Default.ArrowDownward, "Move down") } }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val kinds = if (allowRepeat) StepKind.entries else listOf(StepKind.STEADY, StepKind.RAMP)
                for (k in kinds) {
                    FilterChip(
                        selected = node.kind == k,
                        onClick = {
                            if (k == StepKind.REPEAT && node.children.isEmpty()) {
                                node.times = node.times.ifBlank { "3" }
                                node.children.addAll(EditNode.newRepeat().children)
                            }
                            if (k == StepKind.RAMP && node.from.isBlank()) { node.from = node.power.ifBlank { "50" }; node.to = node.power.ifBlank { "70" } }
                            if (k == StepKind.STEADY && node.power.isBlank()) node.power = node.from.ifBlank { "60" }
                            if (k != StepKind.REPEAT && node.duration.isBlank()) node.duration = "5:00"
                            node.kind = k
                        },
                        label = { Text(when (k) { StepKind.STEADY -> "Steady"; StepKind.RAMP -> "Ramp"; StepKind.REPEAT -> "Intervals" }) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 8.dp)) {
                when (node.kind) {
                    StepKind.STEADY -> {
                        SmallField("Time", node.duration, { node.duration = it }, Modifier.weight(1f), number = false)
                        SmallField("% FTP", node.power, { node.power = it }, Modifier.weight(1f))
                    }
                    StepKind.RAMP -> {
                        SmallField("Time", node.duration, { node.duration = it }, Modifier.weight(1f), number = false)
                        SmallField("From %", node.from, { node.from = it }, Modifier.weight(1f))
                        SmallField("To %", node.to, { node.to = it }, Modifier.weight(1f))
                    }
                    StepKind.REPEAT -> SmallField("Repeat ×", node.times, { node.times = it }, Modifier.weight(1f))
                }
            }
            Row(Modifier.padding(end = 8.dp)) {
                SmallField("Label (optional)", node.label, { node.label = it }, Modifier.fillMaxWidth(), number = false)
            }
            if (node.kind == StepKind.REPEAT) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.padding(start = 8.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    node.children.forEachIndexed { i, child ->
                        androidx.compose.runtime.key(child.uid) {
                            StepCard(
                                node = child,
                                title = "Part ${i + 1}",
                                onUp = if (i > 0) ({ node.children.removeAt(i); node.children.add(i - 1, child) }) else null,
                                onDown = null,
                                onDelete = { node.children.remove(child) },
                                allowRepeat = false,
                            )
                        }
                    }
                    TextButton(onClick = { node.children.add(EditNode.newSteady()) }) { Text("+ Part") }
                }
            }
        }
    }
}

@Composable
private fun SmallField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, number: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
    )
}
