package tw.family.locator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Local alarm/actions and protected system restore broadcasts; never uploads or reads GPS. */
public final class CheckInReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (CheckInScheduler.ACTION_ALARM.equals(action)) {
            CheckInScheduler.onAlarm(context);
        } else if (CheckInScheduler.ACTION_SNOOZE.equals(action)) {
            CheckInScheduler.snooze(context);
        } else if (CheckInScheduler.ACTION_PAUSE.equals(action)) {
            CheckInScheduler.cancel(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            CheckInScheduler.reschedule(context);
        }
    }
}
