package com.vansaska.wattbike.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vansaska.wattbike.core.BikeData
import com.vansaska.wattbike.core.RideRecord
import com.vansaska.wattbike.core.RideSession
import com.vansaska.wattbike.core.RideState
import com.vansaska.wattbike.core.RouteOption
import com.vansaska.wattbike.core.Segment
import com.vansaska.wattbike.core.Step
import com.vansaska.wattbike.core.Workout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed interface Screen {
    data object Home : Screen
    data class Detail(val workoutId: String) : Screen
    data class Edit(val workoutId: String?) : Screen
    data object Ride : Screen
    data class Summary(val rideId: String) : Screen
    data object History : Screen
    data object Settings : Screen
}

/** A snapshot of the ride for the UI, refreshed every second. */
data class RideUi(
    val state: RideState,
    val elapsed: Int,
    val totalSeconds: Int,
    val segments: List<Segment>,
    val segmentLabel: String,
    val nextLabel: String?,
    val nextWatts: Int?,
    val nextCadence: Int?,
    val targetCadence: Int?,
    val segmentLeft: Int,
    val workoutLeft: Int,
    val targetWatts: Int,
    val intensity: Int,
    val power: Int?,
    val cadence: Int?,
    val heartRate: Int?,
    val planComplete: Boolean,
    val workoutName: String,
    val ftp: Int,
    val events: List<String>,
    val inAmnesty: Boolean,
    val amnestyLeft: Int,
    val amnestyLevelPct: Int,
    val energyPct: Int,
    val energyKj: Int,
    val plannedKj: Int,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val workouts = WorkoutRepository(app, http)
    private val rideRepo = RideRepository(app)
    val bike = BikeConnection(app)
    val strava = StravaClient(prefs, http)
    val cues = CuePlayer(app)

    private val _library = MutableStateFlow(workouts.load())
    val library: StateFlow<List<LibraryEntry>> = _library.asStateFlow()

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _rides = MutableStateFlow(rideRepo.all())
    val rides: StateFlow<List<RideRecord>> = _rides.asStateFlow()

    private val _uploading = MutableStateFlow<Set<String>>(emptySet())
    val uploading: StateFlow<Set<String>> = _uploading.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _ftp = MutableStateFlow(prefs.ftp)
    val ftp: StateFlow<Int> = _ftp.asStateFlow()

    private val _stravaStatus = MutableStateFlow(stravaStatusText())
    val stravaStatus: StateFlow<String> = _stravaStatus.asStateFlow()

    private var session: RideSession? = null
    private var rideJob: Job? = null
    private val _ride = MutableStateFlow<RideUi?>(null)
    val ride: StateFlow<RideUi?> = _ride.asStateFlow()

    init {
        prefs.lastDeviceAddress?.let { if (bike.bluetoothEnabled && hasBluetoothPermissions(app)) bike.connect(it) }
    }

    // ---------- navigation ----------

    fun go(s: Screen) { _stack.value = _stack.value + s }
    fun back() { if (_stack.value.size > 1) _stack.value = _stack.value.dropLast(1) }
    private fun replaceTop(s: Screen) { _stack.value = _stack.value.dropLast(1) + s }
    fun say(msg: String) { _message.value = msg }
    fun messageShown() { _message.value = null }

    // ---------- settings ----------

    fun setFtp(v: Int) { prefs.ftp = v; _ftp.value = v }

    private fun stravaStatusText(): String = when {
        strava.isConnected -> "Connected as ${prefs.stravaAthlete ?: "you"}"
        strava.isConfigured -> "Not connected"
        else -> "Enter your Strava API details first"
    }

    fun refreshStrava() { _stravaStatus.value = stravaStatusText() }

    fun handleRedirect(uri: Uri?) {
        if (uri == null || uri.scheme != "wattbikeapp") return
        val code = uri.getQueryParameter("code")
        val err = uri.getQueryParameter("error")
        if (code == null) { say("Strava: ${err ?: "no code returned"}"); return }
        if (uri.getQueryParameter("scope")?.contains("activity:write") == false) {
            say("Strava: please tick the box allowing uploads, then try again")
            return
        }
        viewModelScope.launch {
            runCatching { strava.exchangeCode(code) }
                .onSuccess { say("Connected to Strava") }
                .onFailure { say(it.message ?: "Strava sign-in failed") }
            refreshStrava()
        }
    }

    fun disconnectStrava() { strava.disconnect(); refreshStrava() }

    // ---------- bike ----------

    /** Called once Bluetooth permission is granted. */
    fun onPermissionsGranted() {
        val addr = prefs.lastDeviceAddress
        if (addr != null && bike.state.value.status == LinkStatus.DISCONNECTED && bike.bluetoothEnabled) bike.connect(addr)
    }

    fun connectBike(address: String, name: String) {
        prefs.lastDeviceAddress = address
        prefs.lastDeviceName = name
        bike.connect(address)
    }

    // ---------- library ----------

    fun workout(id: String): LibraryEntry? = _library.value.firstOrNull { it.workout.id == id }

    fun sync() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            runCatching { workouts.sync(prefs.workoutsUrl, _library.value) }
                .onSuccess { _library.value = it.entries; say("Sync: ${it.message}") }
                .onFailure { say("Sync failed: ${it.message}") }
            _syncing.value = false
        }
    }

    fun saveWorkout(w: Workout) {
        val list = _library.value.toMutableList()
        val i = list.indexOfFirst { it.workout.id == w.id }
        if (i >= 0) list[i] = list[i].copy(workout = w, edited = list[i].fromRepo || list[i].edited)
        else list += LibraryEntry(w, fromRepo = false)
        _library.value = list
        workouts.save(list)
    }

    fun deleteWorkout(id: String) {
        val list = _library.value.filterNot { it.workout.id == id }
        _library.value = list
        workouts.save(list)
    }

    fun duplicate(id: String): String? {
        val src = workout(id)?.workout ?: return null
        val copy = src.copy(id = newId(), name = src.name + " (copy)")
        saveWorkout(copy)
        return copy.id
    }

    fun newWorkoutTemplate(): Workout = Workout(
        id = newId(),
        name = "New workout",
        steps = listOf(
            Step.ramp(600, 45, 70, "Warm-up"),
            Step.steady(1200, 75),
            Step.ramp(300, 55, 40, "Cool down"),
        ),
    )

    private fun newId() = "my-" + System.currentTimeMillis().toString(36)

    // ---------- riding ----------

    fun startRide(w: Workout) {
        rideJob?.cancel()
        val s = RideSession(w, prefs.ftp, amnestySeconds = prefs.amnestySeconds)
        session = s
        publish(s)
        go(Screen.Ride)
        rideJob = viewModelScope.launch {
            while (isActive && s.state != RideState.FINISHED) {
                val fresh = SystemClock.elapsedRealtime() - bike.lastDataAt < 3000
                s.tick(System.currentTimeMillis(), if (fresh) bike.data.value else BikeData())
                applyTarget(s)
                s.pollCue()?.let { if (prefs.voiceCues) cues.play(it) }
                publish(s)
                delay(1000)
            }
        }
    }

    /** Fixed resistance during the warm-up amnesty (if the bike supports it), ERG otherwise. */
    private fun applyTarget(s: RideSession) {
        if (s.inAmnesty && bike.state.value.resistanceSupported != false) {
            bike.setFixedResistance(prefs.amnestyLevel.toDouble())
        } else {
            bike.setTargetPower(s.targetWatts)
        }
    }

    fun endAmnesty() { session?.let { it.endAmnesty(); applyTarget(it); publish(it) } }

    fun testCue() { cues.play(com.vansaska.wattbike.core.Cue.SPEED_UP) }

    private fun publish(s: RideSession) {
        val d = bike.data.value
        val fresh = SystemClock.elapsedRealtime() - bike.lastDataAt < 3000
        val seg = s.currentSegment
        val next = s.nextSegment
        _ride.value = RideUi(
            state = s.state,
            elapsed = s.elapsed.toInt(),
            totalSeconds = s.plan.totalSeconds,
            segments = s.plan.segments,
            segmentLabel = seg?.label ?: "Workout complete",
            nextLabel = next?.label,
            nextWatts = next?.let { Math.round(it.fromPct / 100.0 * s.ftp * s.intensity / 100.0).toInt() },
            nextCadence = next?.cadence,
            targetCadence = s.targetCadence,
            segmentLeft = s.secondsLeftInSegment,
            workoutLeft = s.secondsLeftInWorkout,
            targetWatts = s.targetWatts,
            intensity = s.intensity,
            power = if (fresh) d.powerW else null,
            cadence = if (fresh) d.cadenceRpm?.toInt() else null,
            heartRate = if (fresh) d.heartRate else null,
            planComplete = s.planComplete,
            workoutName = s.workoutName,
            ftp = s.ftp,
            events = s.events.map { it.text },
            inAmnesty = s.inAmnesty && bike.state.value.resistanceSupported != false,
            amnestyLeft = s.amnestyLeft,
            amnestyLevelPct = Math.round(prefs.amnestyLevel * 100),
            energyPct = s.energyPct,
            energyKj = s.workKj.toInt(),
            plannedKj = s.plannedKj,
        )
    }

    /** −/+ buttons: change the fixed resistance during the amnesty, otherwise the workout intensity. */
    fun adjustIntensity(delta: Int) {
        val s = session ?: return
        if (s.inAmnesty && bike.state.value.resistanceSupported != false) {
            prefs.amnestyLevel = (prefs.amnestyLevel + delta / 100f).coerceIn(0f, 1f)
            applyTarget(s)
        } else {
            s.adjustIntensity(delta)
        }
        publish(s)
    }

    fun pauseRide() { session?.let { it.pauseManually(System.currentTimeMillis()); publish(it) } }

    fun routeOptions(): List<RouteOption> = session?.routeOptions().orEmpty()

    fun takeRoute(o: RouteOption) {
        session?.let { it.takeRoute(o); applyTarget(it); publish(it) }
    }

    fun finishRide() {
        val s = session ?: return
        rideJob?.cancel()
        s.finish(System.currentTimeMillis())
        bike.releaseControl()
        session = null
        _ride.value = null
        if (s.samples.isEmpty()) {
            back()
            say("Nothing recorded, so nothing saved")
            return
        }
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(s.startedAtMs ?: System.currentTimeMillis()))
        val rec = s.toRecord(id)
        rideRepo.save(rec)
        _rides.value = rideRepo.all()
        replaceTop(Screen.Summary(id))
        if (prefs.autoUpload && strava.isConnected && rec.activeSeconds >= 120) upload(id)
    }

    fun ride(id: String): RideRecord? = _rides.value.firstOrNull { it.id == id }

    fun renameRide(id: String, title: String) {
        val r = ride(id) ?: return
        rideRepo.save(r.copy(title = title.ifBlank { null }))
        _rides.value = rideRepo.all()
    }

    fun deleteRide(id: String) {
        rideRepo.delete(id)
        _rides.value = rideRepo.all()
    }

    fun upload(id: String) {
        val r = ride(id) ?: return
        if (!strava.isConnected) { say("Connect Strava in Settings first"); return }
        if (id in _uploading.value) return
        _uploading.value = _uploading.value + id
        viewModelScope.launch {
            val result = runCatching { strava.upload(r) }
            val latest = ride(id) ?: r
            result.onSuccess {
                rideRepo.save(latest.copy(stravaActivityId = it, uploadError = null))
                say("Uploaded to Strava")
            }.onFailure {
                val msg = it.message ?: "Upload failed"
                // Strava reports a duplicate if this ride was already uploaded.
                rideRepo.save(latest.copy(uploadError = msg))
                say(msg)
            }
            _rides.value = rideRepo.all()
            _uploading.value = _uploading.value - id
        }
    }

    override fun onCleared() {
        cues.shutdown()
        bike.disconnect()
    }
}
