package dk.meningspunktet.unofficial.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the notification poller after device reboot; it idles until a session cookie exists. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        try {
            NotificationPollerService.start(context, NotificationPollerService.ACTION_START, false);
        } catch (Exception ignored) {
            // Some OEM ROMs restrict boot-time FGS starts; MainActivity re-starts the service on next open.
        }
    }
}
