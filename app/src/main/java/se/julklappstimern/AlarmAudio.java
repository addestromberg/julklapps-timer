package se.julklappstimern;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.PowerManager;

final class AlarmAudio {
    static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private AlarmAudio() {}

    static MediaPlayer create(Context context, int resource) {
        MediaPlayer player = MediaPlayer.create(context, resource, ATTRIBUTES,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (player != null) player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
        return player;
    }

    static String blockedReason(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            return "Larmvolymen står på noll. Höj den i ljudinställningarna.";
        }

        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        int filter = notifications.getCurrentInterruptionFilter();
        if (filter == NotificationManager.INTERRUPTION_FILTER_NONE) {
            return "Stör ej tystar larmet. Tillåt larm eller stäng av Stör ej.";
        }
        if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY && Build.VERSION.SDK_INT >= 30) {
            try {
                int categories = notifications.getConsolidatedNotificationPolicy().priorityCategories;
                if ((categories & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0) {
                    return "Stör ej tystar larmet. Tillåt larm eller stäng av Stör ej.";
                }
            } catch (SecurityException inaccessible) {
                // The countdown is the audible check when this device hides its policy.
            }
        }
        return null;
    }
}
