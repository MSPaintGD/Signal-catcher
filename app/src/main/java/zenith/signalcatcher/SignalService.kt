package zenith.signalcatcher

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.*
import androidx.core.content.ContextCompat

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

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val name = (i.action ?: "?").substringAfterLast('.')
            val ex = i.extras?.keySet()?.joinToString(", ") { k ->
                val v = try { i.extras?.get(k) } catch (e: Exception) { "?" }
                "$k=$v"
            } ?: ""
            if (i.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                val m = Telephony.Sms.Intents.getMessagesFromIntent(i)
                Log.add("SMS", "${m.firstOrNull()?.originatingAddress}: ${m.joinToString("") { it.messageBody ?: "" }}")
            } else Log.add("SIG", "$name $ex".take(300))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val ch = "sc"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(ch, "SignalCatcher", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("SignalCatcher listening")
            .setSmallIcon(android.R.drawable.stat_notify_sync).build()
        startForeground(1, n)

        val f = IntentFilter().also { f -> actions.forEach { f.addAction(it) } }
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_EXPORTED)

        if (Build.VERSION.SDK_INT >= 31) try {
            val tm = getSystemService(TelephonyManager::class.java)
            tm.registerTelephonyCallback(mainExecutor, object : TelephonyCallback(),
                TelephonyCallback.SignalStrengthsListener, TelephonyCallback.CellInfoListener,
                TelephonyCallback.ServiceStateListener {
                override fun onSignalStrengthsChanged(s: SignalStrength) { Log.add("CELL", "signal level ${s.level}/4") }
                override fun onCellInfoChanged(c: MutableList<CellInfo>) { Log.add("CELL", "cell info changed (${c.size} cells)") }
                override fun onServiceStateChanged(s: ServiceState) { Log.add("CELL", "service state ${s.state}") }
            })
        } catch (e: SecurityException) { Log.add("ERR", "phone permission missing") }
        Log.add("SYS", "listening started")
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { unregisterReceiver(rx); super.onDestroy() }
}
