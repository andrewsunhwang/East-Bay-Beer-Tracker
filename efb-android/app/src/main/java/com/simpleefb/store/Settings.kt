package com.simpleefb.store

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A persisted preference exposed as a [StateFlow] so Compose screens update when it changes. */
class Pref<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    default: T,
    private val read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) {
    private val state = MutableStateFlow(prefs.read(key, default))
    val flow: StateFlow<T> = state.asStateFlow()
    var value: T
        get() = state.value
        set(v) {
            state.value = v
            prefs.edit().apply { write(key, v) }.apply()
        }
}

enum class PositionSourceType(val label: String) {
    NONE("Off"),
    INTERNAL("Tablet/phone GPS"),
    GDL90_WIFI("ADS-B receiver over Wi-Fi (GDL 90)"),
    BLUETOOTH("Bluetooth device (GTX 345, GPS…)"),
}

class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, d: Boolean) =
        Pref(p, key, d, { k, def -> getBoolean(k, def) }, { k, v -> putBoolean(k, v) })

    private fun int(key: String, d: Int) =
        Pref(p, key, d, { k, def -> getInt(k, def) }, { k, v -> putInt(k, v) })

    private fun string(key: String, d: String) =
        Pref(p, key, d, { k, def -> getString(k, def) ?: def }, { k, v -> putString(k, v) })

    private fun double(key: String, d: Double) =
        Pref(p, key, d, { k, def -> java.lang.Double.longBitsToDouble(getLong(k, def.toRawBits())) },
            { k, v -> putLong(k, v.toRawBits()) })

    val positionSource = string("position_source", PositionSourceType.INTERNAL.name)
    val udpPort = int("udp_port", 4000)
    val btAddress = string("bt_address", "")
    val btName = string("bt_name", "")
    /** Empty = standard Serial Port Profile UUID. */
    val btServiceUuid = string("bt_service_uuid", "")
    val captureRaw = bool("capture_raw", false)

    val autoUpdate = bool("auto_update", true)
    val wifiOnly = bool("wifi_only", true)

    val keepScreenOn = bool("keep_screen_on", true)
    val nightCharts = bool("night_charts", false)

    val mapLayer = string("map_layer", "SECTIONAL")
    val mapLat = double("map_lat", 39.5)
    val mapLon = double("map_lon", -98.35)
    val mapZoom = double("map_zoom", 7.0)
    val trackUp = bool("track_up", false)
    val followOwnship = bool("follow_ownship", true)
    val showTraffic = bool("show_traffic", true)

    fun positionSourceType(): PositionSourceType =
        runCatching { PositionSourceType.valueOf(positionSource.value) }.getOrDefault(PositionSourceType.NONE)
}
