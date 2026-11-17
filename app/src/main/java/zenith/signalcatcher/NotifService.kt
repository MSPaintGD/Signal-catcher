package zenith.signalcatcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifService : NotificationListenerService() {
    override fun onNotificationPosted(s: StatusBarNotification) {
        val e = s.notification.extras
        Log.add("MSG", "${s.packageName}: ${e.getCharSequence("android.title")} - ${e.getCharSequence("android.text")}")
    }
}
