package tw.family.locator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class CalendarReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (CalendarScheduler.ACTION_ALARM.equals(intent.getAction())) {
            CalendarScheduler.deliver(context, intent);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                || Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
            CalendarScheduler.start(context);
            CalendarScheduler.reschedule(context);
        }
    }
}
