package ai.itappens.dailyplanner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Proximity alerts are cleared on reboot and app update, so register them again. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        PlaceAlerts.registerSaved(c);
    }
}
