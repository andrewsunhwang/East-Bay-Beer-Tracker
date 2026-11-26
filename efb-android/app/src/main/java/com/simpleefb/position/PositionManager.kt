package com.simpleefb.position

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.simpleefb.core.OwnshipFix
import com.simpleefb.core.StreamDecoder
import com.simpleefb.core.StreamStats
import com.simpleefb.core.TrafficTarget
import com.simpleefb.store.PositionSourceType
import com.simpleefb.store.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SourceStatus(
    val source: PositionSourceType = PositionSourceType.NONE,
    val state: String = "Off",
    val stats: StreamStats? = null,
    val recentBytesHex: String = "",
    val captureFile: File? = null,
)

/**
 * Owns the active position source and publishes ownship position and traffic.
 *
 * Sources:
 * - Internal GPS (LocationManager)
 * - GDL 90 over Wi-Fi (UDP, default port 4000): Stratux, Sentry, Stratus, SkyEcho, etc.
 * - Bluetooth Classic serial (RFCOMM). The stream is auto-detected as GDL 90 or NMEA, and
 *   can be recorded to a file to diagnose devices with other protocols (e.g. Garmin Connext).
 */
class PositionManager(private val context: Context, private val settings: Settings) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _ownship = MutableStateFlow<OwnshipFix?>(null)
    val ownship: StateFlow<OwnshipFix?> = _ownship.asStateFlow()

    private val _traffic = MutableStateFlow<List<TrafficTarget>>(emptyList())
    val traffic: StateFlow<List<TrafficTarget>> = _traffic.asStateFlow()

    private val _status = MutableStateFlow(SourceStatus())
    val status: StateFlow<SourceStatus> = _status.asStateFlow()

    private val trafficById = ConcurrentHashMap<Int, TrafficTarget>()

    private val lock = Mutex()

    init {
        scope.launch { pruneTrafficLoop() }
    }

    // Start/stop requests are serialized (UNDISPATCHED + a FIFO mutex keeps them in call order),
    // so a quick stop/start never has two sources fighting over a socket.
    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { lock.withLock { startLocked() } }
    }

    fun stop() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            lock.withLock {
                job?.cancelAndJoin()
                job = null
                _status.value = SourceStatus(settings.positionSourceType(), "Paused")
            }
        }
    }

    fun restart() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            lock.withLock {
                job?.cancelAndJoin()
                job = null
                _ownship.value = null
                trafficById.clear()
                _traffic.value = emptyList()
                startLocked()
            }
        }
    }

    private fun startLocked() {
        if (job?.isActive == true) return
        val type = settings.positionSourceType()
        _status.value = SourceStatus(source = type, state = "Starting…")
        job = scope.launch {
            when (type) {
                PositionSourceType.NONE -> _status.value = SourceStatus(type, "Off")
                PositionSourceType.INTERNAL -> runInternalGps()
                PositionSourceType.GDL90_WIFI -> runUdp(settings.udpPort.value)
                PositionSourceType.BLUETOOTH -> runBluetooth()
            }
        }
    }

    private fun setState(state: String) {
        _status.value = _status.value.copy(source = settings.positionSourceType(), state = state)
    }

    // ------------------------------------------------------------------ internal GPS

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun runInternalGps() {
        if (!hasLocationPermission()) {
            setState("Location permission needed (Settings → Position source)")
            awaitCancellation()
        }
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null || !lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            setState("This device has no GPS")
            awaitCancellation()
        }
        setState(if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) "Waiting for GPS fix…" else "Location is turned off")
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { publishLocation(it, stale = true) }
        callbackFlow {
            // All four methods are implemented: on Android 10 and older they are abstract.
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    trySend(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = setState("Waiting for GPS fix…")
                override fun onProviderDisabled(provider: String) = setState("Location is turned off")
            }
            withContext(Dispatchers.Main) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
            awaitClose { lm.removeUpdates(listener) }
        }.collect { publishLocation(it, stale = false) }
    }

    private fun publishLocation(loc: Location, stale: Boolean) {
        val altM: Double? = when {
            Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters
            loc.hasAltitude() -> loc.altitude
            else -> null
        }
        _ownship.value = OwnshipFix(
            latitude = loc.latitude,
            longitude = loc.longitude,
            gpsAltitudeFt = altM?.let { (it * 3.28084).toInt() },
            pressureAltitudeFt = null,
            groundSpeedKt = if (loc.hasSpeed()) loc.speed * 1.943844 else null,
            trackDeg = if (loc.hasBearing() && loc.speed > 1.5f) loc.bearing.toDouble() else null,
            verticalSpeedFpm = null,
            timeMillis = if (stale) loc.time else System.currentTimeMillis(),
            source = "Internal GPS",
        )
        if (!stale) setState("GPS fix (±${loc.accuracy.toInt()} m)")
    }

    // ------------------------------------------------------------------ GDL 90 over Wi-Fi

    private suspend fun runUdp(port: Int) = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("simpleefb-gdl90")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(InetSocketAddress(port))
            }
            setState("Listening for GDL 90 on UDP port $port — join the receiver's Wi-Fi")
            val sink = StreamSink("ADS-B Wi-Fi")
            val buf = ByteArray(65535)
            val packet = DatagramPacket(buf, buf.size)
            while (isActive) {
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                sink.feed(buf, packet.length)
            }
            sink.close()
        } catch (e: IOException) {
            setState("Could not open UDP port $port: ${e.message}")
        } finally {
            socket?.close()
            lock?.release()
        }
    }

    // ------------------------------------------------------------------ Bluetooth

    fun hasBluetoothPermission() = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    data class BtDevice(val name: String, val address: String, val uuids: List<String>)

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BtDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.map { d ->
                BtDevice(d.name ?: d.address, d.address, d.uuids?.map { it.uuid.toString() }.orEmpty())
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    private suspend fun runBluetooth() = withContext(Dispatchers.IO) {
        val address = settings.btAddress.value
        if (address.isEmpty()) {
            setState("Choose a paired Bluetooth device in Settings")
            awaitCancellation()
        }
        if (!hasBluetoothPermission()) {
            setState("Bluetooth permission needed (Settings → Position source)")
            awaitCancellation()
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setState("Bluetooth is off")
            awaitCancellation()
        }
        val device = adapter.getRemoteDevice(address)
        val name = settings.btName.value.ifEmpty { address }
        val uuid = runCatching { UUID.fromString(settings.btServiceUuid.value) }.getOrDefault(SPP_UUID)
        var attempt = 0
        while (isActive) {
            var socket: BluetoothSocket? = null
            try {
                setState("Connecting to $name…")
                runCatching { adapter.cancelDiscovery() }
                val s = connect(device, uuid)
                socket = s
                attempt = 0
                setState("Connected to $name — waiting for data")
                val sink = StreamSink("Bluetooth: $name")
                coroutineScope {
                    // Closing the socket is the only way to unblock read() when cancelled.
                    val watcher = launch { try { awaitCancellation() } finally { runCatching { s.close() } } }
                    try {
                        val input = s.inputStream
                        val buf = ByteArray(4096)
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.feed(buf, n)
                        }
                    } finally {
                        watcher.cancel()
                        sink.close()
                    }
                }
                setState("$name disconnected — reconnecting…")
            } catch (e: IOException) {
                setState("Can't connect to $name (${e.message ?: "error"}). Retrying…")
            } finally {
                runCatching { socket?.close() }
            }
            attempt++
            delay(minOf(20_000L, 2_000L * attempt))
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, uuid: UUID): BluetoothSocket {
        val factories = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(uuid) },
            { device.createInsecureRfcommSocketToServiceRecord(uuid) },
            // Some devices only answer on RFCOMM channel 1 without SDP.
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for (make in factories) {
            val s = try { make() } catch (e: Exception) { last = e; continue }
            try {
                s.connect()
                return s
            } catch (e: IOException) {
                last = e
                runCatching { s.close() }
            }
        }
        throw (last as? IOException) ?: IOException(last?.message ?: "connection failed")
    }

    // ------------------------------------------------------------------ shared stream handling

    /** Decodes a byte stream, publishes results, keeps diagnostics and the optional raw capture. */
    private inner class StreamSink(sourceName: String) {
        private val decoder = StreamDecoder(
            sourceName = sourceName,
            onOwnship = { _ownship.value = it },
            onTraffic = { t ->
                trafficById[t.address] = t
                _traffic.value = trafficById.values.sortedBy { it.address }
            },
        )
        private val recent = ArrayDeque<Byte>()
        private var lastStatusAt = 0L
        private var capture: FileOutputStream? = null
        private var captureFile: File? = null
        private var captured = 0L

        init {
            if (settings.captureRaw.value) {
                val dir = File(context.filesDir, "captures").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val tag = sourceName.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
                captureFile = File(dir, "capture-$tag-$stamp.bin")
                capture = FileOutputStream(captureFile)
            }
        }

        fun feed(buf: ByteArray, n: Int) {
            decoder.feed(buf, 0, n)
            capture?.let { out ->
                if (captured < MAX_CAPTURE_BYTES) {
                    out.write(buf, 0, n)
                    captured += n
                }
            }
            for (i in 0 until n) {
                recent.addLast(buf[i])
                if (recent.size > 96) recent.removeFirst()
            }
            val now = System.currentTimeMillis()
            if (now - lastStatusAt > 500) {
                lastStatusAt = now
                val stats = decoder.stats
                val state = when (stats.protocol) {
                    "GDL 90", "NMEA 0183" -> "Receiving ${stats.protocol}" + (stats.deviceName?.let { " from $it" } ?: "")
                    else -> "Receiving data in an unrecognized format (see diagnostics)"
                }
                _status.value = _status.value.copy(
                    source = settings.positionSourceType(),
                    state = state,
                    stats = stats,
                    recentBytesHex = recent.joinToString(" ") { String.format(Locale.US, "%02X", it) },
                    captureFile = captureFile,
                )
            }
        }

        fun close() {
            runCatching { capture?.close() }
            capture = null
        }
    }

    private suspend fun pruneTrafficLoop() {
        while (true) {
            delay(5_000)
            val cutoff = System.currentTimeMillis() - 20_000
            if (trafficById.values.removeIf { it.timeMillis < cutoff }) {
                _traffic.value = trafficById.values.sortedBy { it.address }
            }
        }
    }

    fun captures(): List<File> =
        File(context.filesDir, "captures").listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val MAX_CAPTURE_BYTES = 50L * 1024 * 1024
    }
}
