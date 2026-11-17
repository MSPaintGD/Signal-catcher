package zenith.signalcatcher

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.*

private class SignalCb : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
    override fun onSignalStrengthsChanged(s: SignalStrength) { Log.add("CELL", "signal level ${s.level}/4") }
}

private class CellCb : TelephonyCallback(), TelephonyCallback.CellInfoListener {
    override fun onCellInfoChanged(c: MutableList<CellInfo>) { Log.add("CELL", "cell info changed (${c.size} cells)") }
}

private class ServiceCb : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
    override fun onServiceStateChanged(s: ServiceState) { Log.add("CELL", "service state ${s.state}") }
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
        "android.net.wifi.SCAN_RESULTS",
        "android.bluetooth.adapter.action.STATE_CHANGED", "android.bluetooth.device.action.FOUND",
        "android.bluetooth.device.action.ACL_CONNECTED", "android.bluetooth.device.action.ACL_DISCONNECTED",
        "android.bluetooth.adapter.action.DISCOVERY_STARTED", "android.bluetooth.adapter.action.DISCOVERY_FINISHED",
        "android.nfc.action.ADAPTER_STATE_CHANGED", "android.nfc.action.TAG_DISCOVERED",
        "android.hardware.usb.action.USB_DEVICE_ATTACHED", "android.hardware.usb.action.USB_DEVICE_DETACHED",
        "android.media.AUDIO_BECOMING_NOISY", "android.media.RINGER_MODE_CHANGED",
        "android.location.PROVIDERS_CHANGED"
    )

    private var receiverRegistered = false
    private val callbacks = ArrayList<TelephonyCallback>()
    private var legacyListener: PhoneStateListener? = null

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
            Log.add("SIG", "$name $ex".take(300))
        }
    }

    private fun show(v: Any?): String = when (v) {
        is Array<*> -> v.contentToString()
        is ByteArray -> v.contentToString()
        is IntArray -> v.contentToString()
        is LongArray -> v.contentToString()
        else -> v.toString()
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
                override fun onCellInfoChanged(c: MutableList<CellInfo>?) { Log.add("CELL", "cell info changed (${c?.size ?: 0} cells)") }
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

    override fun onCreate() {
        super.onCreate()
        val ch = "sc"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "SignalCatcher", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("SignalCatcher listening")
            .setSmallIcon(android.R.drawable.stat_notify_sync).build()
        startForeground(1, n)

        val f = IntentFilter().also { fl -> actions.forEach { fl.addAction(it) } }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rx, f)
        receiverRegistered = true
        Log.add("SYS", "listening started")
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        listenTelephony()
        return START_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTelephony()
        if (receiverRegistered) unregisterReceiver(rx)
        super.onDestroy()
    }
}
