package zenith.signalcatcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifService : NotificationListenerService() {
    override fun onListenerConnected() {
        Log.add("SYS", "notification access active")
    }

    override fun onNotificationPosted(s: StatusBarNotification) {
        if (s.packageName == packageName) return
        val e = s.notification.extras
        Log.add("MSG", "${s.packageName}: ${e.getCharSequence("android.title")} - ${e.getCharSequence("android.text")}")
    }
}
