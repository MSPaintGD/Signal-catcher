package zenith.signalcatcher

import android.app.*
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as BleResult
import android.bluetooth.le.ScanSettings
import android.content.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import android.telephony.*
import kotlin.math.sqrt

private fun describeCell(c: CellInfo): String {
    val reg = if (c.isRegistered) "*" else ""
    return when (c) {
        is CellInfoLte -> "LTE$reg ci=${c.cellIdentity.ci} pci=${c.cellIdentity.pci} ${c.cellSignalStrength.dbm}dBm lvl${c.cellSignalStrength.level}"
        is CellInfoGsm -> "GSM$reg cid=${c.cellIdentity.cid} ${c.cellSignalStrength.dbm}dBm lvl${c.cellSignalStrength.level}"
        is CellInfoWcdma -> "WCDMA$reg cid=${c.cellIdentity.cid} ${c.cellSignalStrength.dbm}dBm lvl${c.cellSignalStrength.level}"
        is CellInfoCdma -> "CDMA$reg ${c.cellSignalStrength.dbm}dBm lvl${c.cellSignalStrength.level}"
        else -> {
            if (Build.VERSION.SDK_INT >= 29 && c is CellInfoNr) {
                val id = c.cellIdentity
                val extra = if (id is CellIdentityNr) "nci=${id.nci} pci=${id.pci} " else ""
                "5G-NR$reg $extra${c.cellSignalStrength.dbm}dBm lvl${c.cellSignalStrength.level}"
            } else "${c.javaClass.simpleName}$reg"
        }
    }
}

private fun logCells(cells: List<CellInfo>?) {
    if (cells == null) return
    Log.add("CELL", "${cells.size} cells visible")
    cells.forEach { Log.add("CELL", "  " + describeCell(it)) }
}

private class SignalCb : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
    override fun onSignalStrengthsChanged(s: SignalStrength) {
        val dbm = if (Build.VERSION.SDK_INT >= 29) s.cellSignalStrengths.joinToString(", ") { "${it.dbm}dBm" } else ""
        Log.add("CELL", "signal level ${s.level}/4 $dbm".trim())
    }
}

private class CellCb : TelephonyCallback(), TelephonyCallback.CellInfoListener {
    override fun onCellInfoChanged(c: MutableList<CellInfo>) { logCells(c) }
}

private class ServiceCb : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
    override fun onServiceStateChanged(s: ServiceState) { Log.add("CELL", "service state ${s.state} operator=${s.operatorAlphaLong}") }
}

class SignalService : Service() {
    private val actions = listOf(
        Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
        "android.intent.action.PHONE_STATE",
        "android.intent.action.BATTERY_CHANGED", "android.intent.action.BATTERY_LOW", "android.intent.action.BATTERY_OKAY",
        "android.intent.action.ACTION_POWER_CONNECTED", "android.intent.action.ACTION_POWER_DISCONNECTED",
        "android.intent.action.AIRPLANE_MODE", "android.intent.action.SCREEN_ON", "android.intent.action.SCREEN_OFF",
        "android.intent.action.USER_PRESENT", "android.intent.action.HEADSET_PLUG",
        "android.intent.action.TIMEZONE_CHANGED", "android.intent.action.TIME_SET", "android.intent.action.LOCALE_CHANGED",
        "android.intent.action.DEVICE_STORAGE_LOW", "android.intent.action.DOCK_EVENT",
        "android.os.action.POWER_SAVE_MODE_CHANGED", "android.os.action.DEVICE_IDLE_MODE_CHANGED",
        "android.net.conn.CONNECTIVITY_CHANGE",
        "android.net.wifi.WIFI_STATE_CHANGED", "android.net.wifi.STATE_CHANGE", "android.net.wifi.RSSI_CHANGED",
        "android.bluetooth.adapter.action.STATE_CHANGED", "android.bluetooth.device.action.FOUND",
        "android.bluetooth.device.action.ACL_CONNECTED", "android.bluetooth.device.action.ACL_DISCONNECTED",
        "android.bluetooth.adapter.action.DISCOVERY_STARTED", "android.bluetooth.adapter.action.DISCOVERY_FINISHED",
        "android.nfc.action.ADAPTER_STATE_CHANGED", "android.nfc.action.TAG_DISCOVERED",
        "android.hardware.usb.action.USB_DEVICE_ATTACHED", "android.hardware.usb.action.USB_DEVICE_DETACHED",
        "android.media.AUDIO_BECOMING_NOISY", "android.media.RINGER_MODE_CHANGED",
        "android.location.PROVIDERS_CHANGED"
    )

    private val main = Handler(Looper.getMainLooper())
    private var receiversRegistered = false
    private val callbacks = ArrayList<TelephonyCallback>()
    private var legacyListener: PhoneStateListener? = null

    private var wifi: WifiManager? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private var locListener: LocationListener? = null
    private var bleCallback: ScanCallback? = null
    private val bleLastSeen = HashMap<String, Long>()
    private var sensorListener: SensorEventListener? = null
    private val sensorLast = HashMap<Int, Long>()
    private var lastGnssLog = 0L
    private var ticking = false

    private fun typeFor(action: String?): String = when {
        action == null -> "SIG"
        action.startsWith("android.net.wifi") -> "WIFI"
        action.startsWith("android.bluetooth") -> "BT"
        action.startsWith("android.nfc") -> "NFC"
        action == "android.location.PROVIDERS_CHANGED" -> "GPS"
        action == "android.intent.action.PHONE_STATE" -> "CELL"
        else -> "SIG"
    }

    private fun show(v: Any?): String = when (v) {
        is Array<*> -> v.contentToString()
        is ByteArray -> v.contentToString()
        is IntArray -> v.contentToString()
        is LongArray -> v.contentToString()
        else -> v.toString()
    }

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                val m: Array<SmsMessage>? = Telephony.Sms.Intents.getMessagesFromIntent(i)
                Log.add("SMS", "${m?.firstOrNull()?.originatingAddress}: ${m?.joinToString("") { it.messageBody ?: "" } ?: ""}")
                return
            }
            val name = (i.action ?: "?").substringAfterLast('.')
            val b = i.extras
            val ex = if (b == null) "" else b.keySet().joinToString(", ") { k ->
                val v: Any? = try { b.get(k) } catch (e: Exception) { "?" }
                "$k=${show(v)}"
            }
            Log.add(typeFor(i.action), "$name $ex".take(300))
        }
    }

    private val wifiRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { reportWifi() }
    }

    private fun channelOf(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5170..5895 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> -1
    }

    @Suppress("DEPRECATION")
    private fun reportWifi() {
        val w = wifi ?: return
        try {
            val list = w.scanResults.sortedByDescending { it.level }
            Log.add("WIFI", "scan: ${list.size} networks")
            list.take(30).forEach {
                val ssid = if (it.SSID.isNullOrEmpty()) "<hidden>" else it.SSID
                Log.add("WIFI", "  $ssid  ${it.level}dBm  ${it.frequency}MHz ch${channelOf(it.frequency)}  ${it.BSSID}")
            }
        } catch (e: SecurityException) {
            Log.add("ERR", "wifi scan results need location permission")
        }
    }

    private val tick = object : Runnable {
        @Suppress("DEPRECATION")
        override fun run() {
            if (!ticking) return
            try {
                val w = wifi
                if (w != null && w.isWifiEnabled) {
                    if (!w.startScan()) reportWifi()
                } else Log.add("WIFI", "Wi-Fi is off")
            } catch (e: SecurityException) {
                Log.add("ERR", "wifi scan not permitted")
            }
            try {
                val tm = getSystemService(TelephonyManager::class.java)
                logCells(tm?.allCellInfo)
            } catch (e: SecurityException) {
                Log.add("ERR", "cell info needs phone + location permission")
            }
            main.postDelayed(this, 35000)
        }
    }

    private fun startWifi() {
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ticking = true
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun startBle() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) { Log.add("BT", "no Bluetooth adapter"); return }
        if (!adapter.isEnabled) { Log.add("BT", "Bluetooth is off, turn it on to scan"); return }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { Log.add("BT", "BLE scanner unavailable"); return }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: BleResult) {
                val addr = r.device.address
                val now = System.currentTimeMillis()
                val last = bleLastSeen[addr]
                if (last != null && now - last < 5000) return
                if (bleLastSeen.size > 500) bleLastSeen.clear()
                bleLastSeen[addr] = now
                val name = r.scanRecord?.deviceName ?: "?"
                Log.add("BT", "BLE $name  $addr  ${r.rssi}dBm")
            }
            override fun onScanFailed(errorCode: Int) { Log.add("ERR", "BLE scan failed: $errorCode") }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), cb)
            bleCallback = cb
            Log.add("BT", "BLE scan started")
        } catch (e: SecurityException) {
            Log.add("ERR", "BLE scan needs Bluetooth + location permission")
        }
    }

    private fun stopBle() {
        val cb = bleCallback ?: return
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) { }
        bleCallback = null
    }

    private fun constellation(t: Int): String = when (t) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        else -> "?"
    }

    @Suppress("DEPRECATION")
    private fun startGnss() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val cb = object : GnssStatus.Callback() {
            override fun onStarted() { Log.add("GPS", "GNSS engine started") }
            override fun onStopped() { Log.add("GPS", "GNSS engine stopped") }
            override fun onFirstFix(ttffMillis: Int) { Log.add("GPS", "first fix after ${ttffMillis / 1000.0}s") }
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val now = System.currentTimeMillis()
                if (now - lastGnssLog < 3000) return
                lastGnssLog = now
                val n = status.satelliteCount
                var used = 0
                var sum = 0f
                val per = HashMap<String, Int>()
                for (k in 0 until n) {
                    if (status.usedInFix(k)) used++
                    sum += status.getCn0DbHz(k)
                    val cn = constellation(status.getConstellationType(k))
                    per[cn] = (per[cn] ?: 0) + 1
                }
                val avg = if (n > 0) sum / n else 0f
                Log.add("GPS", "$n satellites, $used used in fix, avg C/N0 ${"%.1f".format(avg)} dB-Hz")
                if (per.isNotEmpty()) Log.add("GPS", "  " + per.entries.joinToString(", ") { "${it.key}:${it.value}" })
                val best = (0 until n).sortedByDescending { status.getCn0DbHz(it) }.take(5)
                best.forEach {
                    Log.add("GPS", "  ${constellation(status.getConstellationType(it))} #${status.getSvid(it)}  " +
                        "${"%.1f".format(status.getCn0DbHz(it))} dB-Hz  elev ${status.getElevationDegrees(it).toInt()}°")
                }
            }
        }
        try {
            if (lm.registerGnssStatusCallback(cb, main)) gnssCallback = cb
            else Log.add("GPS", "could not register satellite callback")
            val ll = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    Log.add("GPS", "fix ${"%.5f".format(l.latitude)}, ${"%.5f".format(l.longitude)}  ±${l.accuracy.toInt()}m")
                }
                @Deprecated("old API")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
                override fun onProviderEnabled(provider: String) { Log.add("GPS", "$provider enabled") }
                override fun onProviderDisabled(provider: String) { Log.add("GPS", "$provider disabled") }
            }
            if (lm.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, ll)
                locListener = ll
            }
            Log.add("GPS", "satellite listening started")
        } catch (e: SecurityException) {
            Log.add("ERR", "GPS needs location permission")
        }
    }

    @Suppress("DEPRECATION")
    private fun stopGnss() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            gnssCallback?.let { lm.unregisterGnssStatusCallback(it) }
            locListener?.let { lm.removeUpdates(it) }
        } catch (e: Exception) { }
        gnssCallback = null
        locListener = null
    }

    private fun startSensors() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val wanted = listOf(
            Sensor.TYPE_MAGNETIC_FIELD to "magnetic field",
            Sensor.TYPE_LIGHT to "light",
            Sensor.TYPE_PRESSURE to "pressure",
            Sensor.TYPE_PROXIMITY to "proximity",
            Sensor.TYPE_AMBIENT_TEMPERATURE to "ambient temp",
            Sensor.TYPE_RELATIVE_HUMIDITY to "humidity"
        )
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = e.sensor.type
                val now = System.currentTimeMillis()
                if (now - (sensorLast[t] ?: 0L) < 1000) return
                sensorLast[t] = now
                val v = e.values
                val msg = when (t) {
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                        "magnetic ${"%.1f".format(mag)} uT  (x=${"%.1f".format(v[0])} y=${"%.1f".format(v[1])} z=${"%.1f".format(v[2])})"
                    }
                    Sensor.TYPE_LIGHT -> "light ${"%.1f".format(v[0])} lux"
                    Sensor.TYPE_PRESSURE -> "pressure ${"%.2f".format(v[0])} hPa"
                    Sensor.TYPE_PROXIMITY -> "proximity ${v[0]} cm"
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> "ambient temp ${"%.1f".format(v[0])} C"
                    Sensor.TYPE_RELATIVE_HUMIDITY -> "humidity ${"%.1f".format(v[0])} %"
                    else -> return
                }
                Log.add("SENS", msg)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) { }
        }
        var any = false
        for ((type, label) in wanted) {
            val s = sm.getDefaultSensor(type)
            if (s == null) { Log.add("SENS", "no $label sensor on this phone"); continue }
            if (sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)) any = true
        }
        if (any) sensorListener = l
    }

    private fun stopSensors() {
        val l = sensorListener ?: return
        (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(l)
        sensorListener = null
    }

    @Suppress("DEPRECATION")
    private fun listenTelephony() {
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        stopTelephony()
        if (Build.VERSION.SDK_INT >= 31) {
            val candidates = listOf<TelephonyCallback>(SignalCb(), CellCb(), ServiceCb())
            for (cb in candidates) {
                try {
                    tm.registerTelephonyCallback(mainExecutor, cb)
                    callbacks.add(cb)
                } catch (e: SecurityException) {
                    Log.add("ERR", "${cb.javaClass.simpleName} needs a permission that is missing")
                }
            }
        } else {
            val l = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(s: SignalStrength?) { Log.add("CELL", "signal level ${s?.level}/4") }
                override fun onCellInfoChanged(c: MutableList<CellInfo>?) { logCells(c) }
                override fun onServiceStateChanged(s: ServiceState?) { Log.add("CELL", "service state ${s?.state}") }
            }
            try {
                tm.listen(l, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_CELL_INFO or PhoneStateListener.LISTEN_SERVICE_STATE)
                legacyListener = l
            } catch (e: SecurityException) {
                Log.add("ERR", "phone permission missing")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopTelephony() {
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            callbacks.forEach { tm.unregisterTelephonyCallback(it) }
            callbacks.clear()
        } else {
            legacyListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            legacyListener = null
        }
    }

    private fun stopAll() {
        ticking = false
        main.removeCallbacks(tick)
        stopTelephony()
        stopBle()
        stopGnss()
        stopSensors()
    }

    private fun startAll() {
        stopAll()
        listenTelephony()
        startWifi()
        startBle()
        startGnss()
        startSensors()
    }

    override fun onCreate() {
        super.onCreate()
        val ch = "sc"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "SignalCatcher", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("SignalCatcher listening")
            .setSmallIcon(android.R.drawable.stat_notify_sync).build()
        startForeground(1, n)

        val f = IntentFilter().also { fl -> actions.forEach { fl.addAction(it) } }
        val wf = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(wifiRx, wf, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(rx, f)
            registerReceiver(wifiRx, wf)
        }
        receiversRegistered = true
        Log.add("SYS", "listening started")
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        startAll()
        return START_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAll()
        if (receiversRegistered) {
            unregisterReceiver(rx)
            unregisterReceiver(wifiRx)
        }
        super.onDestroy()
    }
}
