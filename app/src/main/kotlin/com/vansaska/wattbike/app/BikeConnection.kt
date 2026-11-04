package com.vansaska.wattbike.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.vansaska.wattbike.core.BikeData
import com.vansaska.wattbike.core.Ftms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LinkStatus { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

data class BikeState(
    val status: LinkStatus = LinkStatus.DISCONNECTED,
    val deviceName: String? = null,
    val hasControl: Boolean = false,
    /** Whether the bike accepts a fixed resistance level (null until known). */
    val resistanceSupported: Boolean? = null,
    val message: String? = null,
)

data class FoundDevice(val address: String, val name: String, val rssi: Int)

/**
 * Talks to the bike over Bluetooth using the standard Fitness Machine Service (FTMS).
 * All GATT work happens on the main thread, one operation at a time.
 */
@SuppressLint("MissingPermission")
class BikeConnection(private val context: Context) {
    private val tag = "BikeConnection"
    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _state = MutableStateFlow(BikeState())
    val state: StateFlow<BikeState> = _state.asStateFlow()

    private val _data = MutableStateFlow(BikeData())
    val data: StateFlow<BikeData> = _data.asStateFlow()

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found.asStateFlow()

    /** Time of the last data notification, to spot a silent connection. */
    @Volatile var lastDataAt: Long = 0
        private set

    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var wantAddress: String? = null
    private var reconnectAttempts = 0

    private val ops = ArrayDeque<() -> Boolean>()
    private var busy = false
    private val opTimeout = Runnable { Log.w(tag, "GATT op timed out"); busy = false; nextOp() }

    /** What the bike should be doing: hold a power (ERG) or hold a resistance level. */
    sealed interface Target {
        data class Power(val watts: Int) : Target
        data class Resistance(val fraction: Double) : Target
    }

    private var desired: Target? = null
    private var sent: Target? = null
    private var lastSentAt = 0L
    private var range: Ftms.ResistanceRange? = null
    private var wideResistance = false
    private var triedWide = false

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    // ---------- scanning ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: dev.name ?: "Unknown (${dev.address})"
            val list = _found.value.filter { it.address != dev.address } + FoundDevice(dev.address, name, result.rssi)
            _found.value = list.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            update { it.copy(status = LinkStatus.DISCONNECTED, message = "Scan failed ($errorCode)") }
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            update { it.copy(message = "Bluetooth is off") }
            return
        }
        _found.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Ftms.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        if (_state.value.status == LinkStatus.DISCONNECTED) update { it.copy(status = LinkStatus.SCANNING, message = null) }
        main.postDelayed({ stopScan() }, 15_000)
    }

    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_state.value.status == LinkStatus.SCANNING) update { it.copy(status = LinkStatus.DISCONNECTED) }
    }

    // ---------- connecting ----------

    fun connect(address: String) {
        stopScan()
        wantAddress = address
        reconnectAttempts = 0
        openGatt(address)
    }

    private fun openGatt(address: String) {
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: return
        closeGatt()
        update { it.copy(status = LinkStatus.CONNECTING, deviceName = device.name ?: it.deviceName, hasControl = false, message = null) }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        wantAddress = null
        main.removeCallbacksAndMessages(null)
        gatt?.let { g ->
            controlPoint?.let { cp -> runCatching { writeNow(g, cp, Ftms.reset()) } }
        }
        main.postDelayed({ closeGatt(); update { BikeState() } }, 300)
    }

    private fun closeGatt() {
        ops.clear()
        busy = false
        main.removeCallbacks(opTimeout)
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        controlPoint = null
        sent = null
    }

    // ---------- control ----------

    /** Sets the ERG target. Sends at most once a second and only when the value changes. */
    fun setTargetPower(watts: Int) {
        desired = Target.Power(watts)
        main.post { maybeSend() }
    }

    /**
     * Holds a fixed resistance, as a fraction (0..1) of the bike's range. Unlike ERG, this doesn't
     * react to cadence, so stopping briefly doesn't make it surge.
     */
    fun setFixedResistance(fraction: Double) {
        desired = Target.Resistance(fraction)
        main.post { maybeSend() }
    }

    /** Tells the bike to stop holding a target (back to free riding). */
    fun releaseControl() {
        desired = null
        main.post {
            val g = gatt ?: return@post
            val cp = controlPoint ?: return@post
            enqueue { writeNow(g, cp, Ftms.reset()) }
            sent = null
            update { it.copy(hasControl = false) }
        }
    }

    private fun maybeSend() {
        val g = gatt ?: return
        val cp = controlPoint ?: return
        val t = desired ?: return
        if (!_state.value.hasControl) return
        val now = SystemClock.elapsedRealtime()
        if (t == sent && now - lastSentAt < 15_000) return // resend now and then in case it was missed
        if (now - lastSentAt < 1000) return
        lastSentAt = now
        sent = t
        val bytes = when (t) {
            is Target.Power -> Ftms.setTargetPower(t.watts)
            is Target.Resistance -> {
                if (_state.value.resistanceSupported == false) return
                val rg = range ?: Ftms.ResistanceRange(0, 200, 1)
                Ftms.setTargetResistance(rg.rawAt(t.fraction), wideResistance)
            }
        }
        enqueue { writeNow(g, cp, bytes) }
    }

    // ---------- GATT callback ----------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
            if (g != gatt) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectAttempts = 0
                update { it.copy(status = LinkStatus.CONNECTING, message = "Discovering services…") }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt()
                val addr = wantAddress
                if (addr != null && reconnectAttempts < 20) {
                    reconnectAttempts++
                    update { it.copy(status = LinkStatus.CONNECTING, hasControl = false, message = "Connection lost, reconnecting…") }
                    main.postDelayed({ if (wantAddress == addr) openGatt(addr) }, 2000)
                } else {
                    update { it.copy(status = LinkStatus.DISCONNECTED, hasControl = false, message = "Disconnected") }
                }
            }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
            if (g != gatt) return@post
            val svc = g.getService(Ftms.SERVICE)
            if (svc == null) {
                update { it.copy(message = "This device doesn't offer Bluetooth fitness machine control") }
                return@post
            }
            val data = svc.getCharacteristic(Ftms.INDOOR_BIKE_DATA)
            val cp = svc.getCharacteristic(Ftms.CONTROL_POINT)
            controlPoint = cp
            update { it.copy(status = LinkStatus.CONNECTED, message = null) }
            svc.getCharacteristic(Ftms.FEATURE)?.let { c -> enqueue { g.readCharacteristic(c) } }
            svc.getCharacteristic(Ftms.RESISTANCE_RANGE)?.let { c -> enqueue { g.readCharacteristic(c) } }
            if (data != null) enqueue { subscribe(g, data, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
            if (cp != null) {
                enqueue { subscribe(g, cp, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) }
                enqueue { writeNow(g, cp, Ftms.requestControl()) }
            } else {
                update { it.copy(message = "Bike doesn't accept control commands") }
            }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(tag, "Descriptor write failed: $status")
            opDone()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(tag, "Write failed: $status")
            opDone()
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onRead(c, value.copyOf(), status)
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onRead(c, c.value?.copyOf() ?: ByteArray(0), status)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleValue(c, value)
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handleValue(c, c.value ?: return)
            }
        }
    }

    private fun onRead(c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        main.post {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (c.uuid) {
                    Ftms.FEATURE -> Ftms.parseFeatures(value)?.let { f ->
                        Log.i(tag, "Features: $f")
                        update { it.copy(resistanceSupported = f.resistanceTarget) }
                    }
                    Ftms.RESISTANCE_RANGE -> Ftms.parseResistanceRange(value)?.let { rg ->
                        Log.i(tag, "Resistance range: $rg")
                        range = rg
                        wideResistance = rg.max > 255
                    }
                }
            }
            opDone()
        }
    }

    private fun handleValue(c: BluetoothGattCharacteristic, value: ByteArray) {
        val copy = value.copyOf()
        main.post {
            when (c.uuid) {
                Ftms.INDOOR_BIKE_DATA -> Ftms.parseIndoorBikeData(copy)?.let {
                    _data.value = _data.value.merge(it)
                    lastDataAt = SystemClock.elapsedRealtime()
                }
                Ftms.CONTROL_POINT -> Ftms.parseControlPointResponse(copy)?.let { r -> onControlResponse(r.requestOpCode, r.success, r.resultText) }
            }
        }
    }

    private fun onControlResponse(op: Int, ok: Boolean, text: String) {
        val g = gatt ?: return
        val cp = controlPoint ?: return
        when (op) {
            Ftms.OP_REQUEST_CONTROL -> if (ok) {
                update { it.copy(hasControl = true, message = null) }
                enqueue { writeNow(g, cp, Ftms.startOrResume()) }
                sent = null
                lastSentAt = 0
                maybeSend()
            } else {
                update { it.copy(hasControl = false, message = "Bike refused control ($text). Is another app connected?") }
                main.postDelayed({ if (gatt == g) enqueue { writeNow(g, cp, Ftms.requestControl()) } }, 3000)
            }
            Ftms.OP_SET_TARGET_RESISTANCE -> if (!ok) {
                Log.w(tag, "Set resistance failed: $text (wide=$wideResistance)")
                if (text == "control not permitted") {
                    update { it.copy(hasControl = false) }
                    enqueue { writeNow(g, cp, Ftms.requestControl()) }
                } else if (!triedWide) {
                    // Try the other parameter size once before giving up.
                    triedWide = true
                    wideResistance = !wideResistance
                    sent = null
                    lastSentAt = 0
                    main.postDelayed({ maybeSend() }, 300)
                } else {
                    update { it.copy(resistanceSupported = false) }
                }
            }
            Ftms.OP_SET_TARGET_POWER -> if (!ok) {
                Log.w(tag, "Set target power failed: $text")
                if (text == "control not permitted") {
                    update { it.copy(hasControl = false) }
                    enqueue { writeNow(g, cp, Ftms.requestControl()) }
                }
            }
        }
    }

    // ---------- operation queue ----------

    private fun enqueue(op: () -> Boolean) {
        ops.addLast(op)
        if (!busy) nextOp()
    }

    private fun nextOp() {
        while (true) {
            val op = ops.removeFirstOrNull() ?: run { busy = false; return }
            busy = true
            val started = runCatching { op() }.getOrElse { Log.w(tag, "op threw", it); false }
            if (started) {
                main.postDelayed(opTimeout, 3000)
                return
            }
        }
    }

    private fun opDone() {
        main.removeCallbacks(opTimeout)
        busy = false
        nextOp()
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Ftms.CCCD) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun writeNow(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    private fun update(f: (BikeState) -> BikeState) {
        _state.value = f(_state.value)
    }
}
