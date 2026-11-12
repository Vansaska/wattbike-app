package com.vansaska.wattbike.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vansaska.wattbike.app.LinkStatus
import com.vansaska.wattbike.app.MainViewModel
import com.vansaska.wattbike.app.R
import com.vansaska.wattbike.app.RideUi
import com.vansaska.wattbike.core.Cue
import com.vansaska.wattbike.core.Durations
import com.vansaska.wattbike.core.RideState
import com.vansaska.wattbike.core.RouteOption
import com.vansaska.wattbike.core.Segment
import com.vansaska.wattbike.core.kilojoules
import kotlin.math.abs
import kotlin.math.pow

// ---------- look ----------

private val Paper = Color(0xFFF3F1EE)
private val Ink = Color(0xFF0E0E0E)

val InterTight = FontFamily(
    Font(R.font.inter_tight, FontWeight.Medium),
    Font(R.font.inter_tight, FontWeight.SemiBold),
    Font(R.font.inter_tight, FontWeight.Bold),
)

/** How your cadence compares with the step's target. Drives the blob's colour. */
/** How close your cadence is to the step's target, Wattbike-style: green on pace, yellow near, red off. */
enum class Pace { NONE, ON, NEAR, OFF }

data class PaceReading(val pace: Pace, val word: String)

fun paceOf(cadence: Int?, aim: Int?): PaceReading {
    if (aim == null || cadence == null || cadence < 20) return PaceReading(Pace.NONE, "")
    val diff = cadence - aim
    return when {
        abs(diff) <= 4 -> PaceReading(Pace.ON, "on pace")
        abs(diff) <= 9 -> PaceReading(Pace.NEAR, if (diff < 0) "a bit faster" else "a bit slower")
        else -> PaceReading(Pace.OFF, if (diff < 0) "speed up" else "ease off")
    }
}

private data class BlobPalette(val ring: Color, val mid: Color, val core: Color, val hot: Color, val dot: Color)

private fun paletteFor(p: Pace) = when (p) {
    Pace.ON -> BlobPalette(Color(0xFF14935E), Color(0xFF3FD46E), Color(0xFFD8F53A), Color(0xFFFFF27A), Color(0xFF14935E))
    Pace.NEAR -> BlobPalette(Color(0xFFD08A00), Color(0xFFFFC21A), Color(0xFFFFE95A), Color(0xFFFFF7B8), Color(0xFFE0A800))
    Pace.OFF -> BlobPalette(Color(0xFFB3123B), Color(0xFFFF3B1C), Color(0xFFFF7A3D), Color(0xFFFFB84A), Color(0xFFD8201C))
    Pace.NONE -> BlobPalette(Color(0xFF5B3BE8), Color(0xFFF06BE0), Color(0xFFFFE14A), Color(0xFFFF4A1C), Color(0xFF8A8A8A))
}

// ---------- screen ----------

/** Landscape ride screen. The state comes from the view model; [RideContent] draws it. */
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

    val problem = when {
        bike.status != LinkStatus.CONNECTED -> "bike not connected"
        !bike.hasControl -> "no control of the bike"
        else -> null
    }

    RideContent(
        r = r,
        problem = problem,
        onAdjust = { vm.adjustIntensity(it) },
        onRoute = { routes = vm.routeOptions() },
        onEndAmnesty = { vm.endAmnesty() },
        onPause = { vm.pauseRide() },
        onFinish = { confirmFinish = true },
    )

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

/** Big figures: tabular digits, and no extra space above and below the glyphs. */
private val BigNumber = TextStyle(
    fontFeatureSettings = "tnum",
    lineHeight = 0.9.em,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

private val stepCountRegex = Regex("""^(.*) \((\d+/\d+)\)$""")

/** The ride screen's layout, with no dependency on the view model (also used for screenshots). */
@Composable
fun RideContent(
    r: RideUi,
    problem: String?,
    onAdjust: (Int) -> Unit = {},
    onRoute: () -> Unit = {},
    onEndAmnesty: () -> Unit = {},
    onPause: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    val aim = r.targetCadence
    val reading = paceOf(r.cadence, aim)
    val palette = paletteFor(reading.pace)
    // How much of the current phase is left: the blob shrinks with it and is gone as it ends.
    val phaseLeft = when {
        r.inAmnesty -> if (r.amnestyTotal > 0) r.amnestyLeft.toFloat() / r.amnestyTotal else 1f
        r.planComplete -> 0f
        r.segmentDuration > 0 -> r.segmentLeft.toFloat() / r.segmentDuration
        else -> 1f
    }
    val phaseKey = if (r.inAmnesty) -2 else r.segmentIndex
    val ticking = r.state == RideState.RUNNING

    val (stepName, stepCount) = when {
        r.state == RideState.WAITING -> "pedal to start" to ""
        r.state == RideState.PAUSED -> "paused" to ""
        r.inAmnesty -> "warm-up" to "free"
        r.planComplete -> "done" to "tap finish"
        else -> stepCountRegex.find(r.segmentLabel)?.let { it.groupValues[1].lowercase() to "(${it.groupValues[2]})" }
            ?: (r.segmentLabel.lowercase() to "")
    }
    val countdown = when {
        r.inAmnesty -> r.amnestyLeft
        r.planComplete -> 0
        else -> r.segmentLeft
    }

    Surface(color = Paper, contentColor = Ink, modifier = Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // Scale the big type to the height available, so everything fits on shorter phones.
            val k = (maxHeight / 300.dp).coerceIn(0.5f, 1f)
            val blobMax = minOf(maxHeight * 1.18f, maxWidth * 0.5f)
            Row(Modifier.fillMaxSize()) {
                // Left: cadence, the number you control, on its colour signal.
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    CadenceBlob(reading.pace, phaseLeft, phaseKey, ticking, blobMax)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("rpm", fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = (15 * k).sp, modifier = Modifier.padding(bottom = 4.dp))
                        Text(
                            r.cadence?.toString() ?: "–",
                            fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = (128 * k).sp,
                            letterSpacing = (-0.06).em, style = BigNumber,
                        )
                        // The target: big, since it's what you're steering towards.
                        Row(modifier = Modifier.padding(top = (6 * k).dp)) {
                            Text(
                                if (aim != null) "aim " else "no target",
                                fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = (24 * k).sp,
                                letterSpacing = (-0.02).em, modifier = Modifier.alignByBaseline(),
                            )
                            if (aim != null) {
                                Text(
                                    "$aim",
                                    fontFamily = InterTight, fontWeight = FontWeight.Bold, fontSize = (60 * k).sp,
                                    letterSpacing = (-0.04).em, modifier = Modifier.alignByBaseline(),
                                    style = TextStyle(fontFeatureSettings = "tnum", platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                )
                            }
                        }
                        if (reading.word.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size((14 * k).dp).background(palette.dot, CircleShape))
                                Spacer(Modifier.width(7.dp))
                                Text(reading.word, fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = (22 * k).sp, letterSpacing = (-0.02).em)
                            }
                        }
                    }
                }
                // Right: where you are in the step.
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp), verticalArrangement = Arrangement.Center) {
                    Text(
                        buildAnnotatedString {
                            append(stepName)
                            if (stepCount.isNotEmpty()) {
                                withStyle(SpanStyle(fontSize = (16 * k).sp, fontWeight = FontWeight.Medium, letterSpacing = 0.em)) { append("  $stepCount") }
                            }
                        },
                        fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = (36 * k).sp, letterSpacing = (-0.03).em, maxLines = 1,
                    )
                    Text(
                        Durations.format(countdown),
                        fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = (132 * k).sp,
                        letterSpacing = (-0.06).em, style = BigNumber,
                    )
                    Text(
                        buildAnnotatedString {
                            append("power ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${r.power ?: "–"} W") }
                            append(if (r.inAmnesty) " · resistance ${r.amnestyLevelPct}%" else " · target ${r.targetWatts} W")
                        },
                        fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = (24 * k).sp, letterSpacing = (-0.02).em, maxLines = 1,
                    )
                }
            }
            }

            RideGraph(r.segments, r.elapsed, Modifier.fillMaxWidth().height(66.dp).padding(top = 6.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("energy ${r.energyPct}%", fontFamily = InterTight, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).height(3.dp).background(Ink.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((r.energyPct / 100f).coerceIn(0f, 1f)).background(Ink))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "${r.energyKj} of ${r.plannedKj} kJ · ${Durations.format(r.workoutLeft)} left",
                    fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                )
            }

            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(top = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill("−", Modifier.width(44.dp), "Easier") { onAdjust(-5) }
                Text(
                    if (r.inAmnesty) "${r.amnestyLevelPct}%" else "${r.intensity}%",
                    fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Pill("+", Modifier.width(44.dp), "Harder") { onAdjust(5) }
                if (r.inAmnesty) {
                    Pill("start ERG now", Modifier.weight(1.6f), filled = true, onClick = onEndAmnesty)
                } else {
                    Pill("take a different route", Modifier.weight(1.6f), filled = true, enabled = !r.planComplete, onClick = onRoute)
                }
                Pill("pause", Modifier.weight(1f), enabled = r.state == RideState.RUNNING, onClick = onPause)
                Pill("finish", Modifier.weight(1f), onClick = onFinish)
            }
        }
        if (problem != null) {
            Text(
                problem,
                fontFamily = InterTight, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 14.dp)
                    .background(Color(0xFFC81E1E), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
      }
    }
}

@Composable
private fun Pill(
    text: String,
    modifier: Modifier,
    description: String? = null,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp).let { m ->
            if (description != null) m.then(Modifier.semantics { contentDescription = description }) else m
        },
        shape = RoundedCornerShape(22.dp),
        border = if (filled) null else BorderStroke(1.5.dp, if (enabled) Ink else Ink.copy(alpha = 0.3f)),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (filled) Ink else Color.Transparent,
            contentColor = if (filled) Paper else Ink,
            disabledContainerColor = if (filled) Ink.copy(alpha = 0.3f) else Color.Transparent,
            disabledContentColor = if (filled) Paper else Ink.copy(alpha = 0.3f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        Text(
            text, fontFamily = InterTight, fontWeight = FontWeight.SemiBold,
            fontSize = if (filled) 18.sp else if (text.length == 1) 22.sp else 15.sp,
            letterSpacing = (-0.01).em, maxLines = 1,
        )
    }
}

/**
 * The colour blob behind the cadence. Colour = how close your cadence is to the target
 * (cross-fades in 0.3 s). Size = how much of the phase is left: it starts big, shrinks as the
 * phase runs and is gone as it ends; when the next phase starts it bursts back in a flicker.
 */
@Composable
fun CadenceBlob(pace: Pace, phaseLeft: Float, phaseKey: Int, ticking: Boolean, maxSize: Dp) {
    val pal = paletteFor(pace)
    val fade = tween<Color>(300)
    val ring by animateColorAsState(pal.ring, fade, label = "ring")
    val mid by animateColorAsState(pal.mid, fade, label = "mid")
    val core by animateColorAsState(pal.core, fade, label = "core")
    val hot by animateColorAsState(pal.hot, fade, label = "hot")

    // Shrink smoothly between the once-a-second updates, aiming at where it will be next second.
    val shrink = remember { Animatable(phaseLeft.coerceIn(0f, 1f)) }
    val nova = remember { Animatable(1f) }
    var lastKey by remember { mutableStateOf(phaseKey) }
    LaunchedEffect(phaseKey) {
        if (phaseKey != lastKey) {
            lastKey = phaseKey
            shrink.snapTo(1f)
            nova.snapTo(0f)
            nova.animateTo(1f, tween(1200, easing = LinearEasing))
        }
    }
    LaunchedEffect(phaseLeft, ticking) {
        if (nova.value < 1f) return@LaunchedEffect
        if (ticking) shrink.animateTo(phaseLeft.coerceIn(0f, 1f), tween(1000, easing = LinearEasing))
        else shrink.snapTo(phaseLeft.coerceIn(0f, 1f))
    }
    val (novaScale, novaAlpha) = novaFrame(nova.value)
    // Radius follows a gentle curve so the blob stays readable for most of the phase.
    val scale = shrink.value.coerceIn(0f, 1f).pow(0.6f) * novaScale

    Canvas(
        Modifier
            .requiredSize(maxSize)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = novaAlpha }
            .blur(14.dp, BlurredEdgeTreatment.Unbounded),
    ) {
        if (scale <= 0.01f) return@Canvas
        val r = this.size.minDimension / 2
        val c = center
        drawCircle(
            Brush.radialGradient(
                0f to Color.Transparent, 0.52f to Color.Transparent, 0.62f to ring.copy(alpha = 0.9f),
                0.74f to ring.copy(alpha = 0.9f), 0.8f to Color.Transparent,
                center = c, radius = r,
            ),
            radius = r, center = c,
        )
        val d = r * 2 * 0.76f
        val tl = Offset(c.x - d / 2, c.y - d / 2)
        drawCircle(
            Brush.radialGradient(0f to core, 0.55f to mid, 0.75f to mid.copy(alpha = 0f), 1f to Color.Transparent, center = c, radius = d / 2),
            radius = d / 2, center = c,
        )
        val midC = Offset(tl.x + d * 0.66f, tl.y + d * 0.42f)
        drawCircle(
            Brush.radialGradient(0f to mid, 0.4f to mid, 1f to mid.copy(alpha = 0f), center = midC, radius = d * 0.4f),
            radius = d * 0.4f, center = midC,
        )
        val hotC = Offset(tl.x + d * 0.38f, tl.y + d * 0.66f)
        drawCircle(
            Brush.radialGradient(0f to hot, 0.45f to core, 1f to core.copy(alpha = 0f), center = hotC, radius = d * 0.42f),
            radius = d * 0.42f, center = hotC,
        )
    }
}

/** The burst at the start of a phase: grows from nothing past full size in strobing steps, then settles. */
private fun novaFrame(p: Float): Pair<Float, Float> = when {
    p >= 1f -> 1f to 1f
    p < 0.05f -> 0.15f to 1f
    p < 0.10f -> 0.45f to 0.3f
    p < 0.16f -> 0.85f to 1f
    p < 0.22f -> 1.25f to 0.35f
    p < 0.30f -> 1.65f to 1f
    p < 0.38f -> 1.95f to 0.5f   // peak: nearly twice full size, spilling across the screen
    p < 0.46f -> 1.85f to 1f
    p < 0.55f -> 1.55f to 0.55f
    p < 0.65f -> 1.30f to 1f
    p < 0.76f -> 1.12f to 0.75f
    p < 0.88f -> 1.04f to 1f
    else -> 1f to 1f
}

/** Workout profile: efforts solid ink, the rest pale; done parts faded; a line marks now. */
@Composable
fun RideGraph(segments: List<Segment>, elapsed: Int, modifier: Modifier) {
    Canvas(modifier) {
        val total = segments.lastOrNull()?.end ?: return@Canvas
        if (total <= 0) return@Canvas
        val top = 8.dp.toPx()
        val h = size.height - top
        val peak = (segments.maxOf { it.maxPct } * 1.05).coerceAtLeast(60.0)
        val gap = 3.dp.toPx()
        fun x(t: Int) = t.toFloat() / total * size.width
        fun y(p: Double) = size.height - (p / peak * h).toFloat()
        for (s in segments) {
            val x0 = x(s.start) + gap / 2
            val x1 = (x(s.end) - gap / 2).coerceAtLeast(x0 + 1f)
            val path = Path().apply {
                moveTo(x0, size.height); lineTo(x0, y(s.fromPct)); lineTo(x1, y(s.toPct)); lineTo(x1, size.height); close()
            }
            val effort = s.avgPct > 75
            val base = if (effort) Ink else Ink.copy(alpha = 0.16f)
            drawPath(path, if (s.end <= elapsed) base.copy(alpha = base.alpha * 0.3f) else base)
        }
        val px = x(elapsed.coerceIn(0, total))
        drawRect(Ink, topLeft = Offset(px - 1.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
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
