package se.julklappstimern;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

public final class AlarmPlayer extends Service {
    static final int NOTIFICATION_ID = 1;
    static final String CHANNEL = "slutlarm";
    private static final String FALLBACK = "slutlarm-reserv";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::complete;
    private MediaPlayer player;
    private String id;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String requested = intent == null ? null : intent.getStringExtra(Rounds.EXTRA_ROUND);
        Rounds rounds = new Rounds(this);
        Rounds.Round round = rounds.current();
        long remaining = round.deadline() - SystemClock.elapsedRealtime();
        if (!round.id().equals(requested) || round.phase() != Rounds.Phase.RINGING || remaining <= 0) {
            if (round.id().equals(requested) && round.phase() == Rounds.Phase.RINGING) rounds.finish(requested);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (requested.equals(id)) return START_REDELIVER_INTENT;

        id = requested;
        createChannels(this);
        startForeground(NOTIFICATION_ID, notification(this, CHANNEL, id));
        player = AlarmAudio.create(this, R.raw.slutlarm);
        if (player != null) {
            player.setLooping(true);
            player.setOnErrorListener((failed, what, extra) -> {
                release();
                postFallback(this, id);
                return true;
            });
            try {
                player.start();
            } catch (IllegalStateException unavailable) {
                release();
                postFallback(this, id);
            }
        } else {
            postFallback(this, id);
        }
        handler.postDelayed(timeout, remaining);
        return START_REDELIVER_INTENT;
    }

    private void complete() {
        if (id != null) new Rounds(this).finish(id);
        stopSelf();
    }

    private void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(timeout);
        release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel primary = new NotificationChannel(CHANNEL, "Slutlarm", NotificationManager.IMPORTANCE_HIGH);
        primary.setDescription("Larm och knappen Tysta larm när rundan är klar.");
        primary.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        primary.setSound(null, null);
        manager.createNotificationChannel(primary);

        NotificationChannel fallback = new NotificationChannel(FALLBACK, "Reservlarm", NotificationManager.IMPORTANCE_HIGH);
        fallback.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        fallback.setSound(Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"
                + context.getPackageName() + "/raw/slutlarm"), AlarmAudio.ATTRIBUTES);
        manager.createNotificationChannel(fallback);
    }

    static void postFallback(Context context, String id) {
        createChannels(context);
        context.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                notification(context, FALLBACK, id));
    }

    private static Notification notification(Context context, String channel, String id) {
        Intent open = new Intent(context, MainActivity.class).setAction(Rounds.ACTION_SHOW_ALARM)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Rounds.EXTRA_ROUND, id);
        Intent silence = new Intent(context, AlarmReceiver.class).setAction(Rounds.ACTION_SILENCE)
                .putExtra(Rounds.EXTRA_ROUND, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openAction = PendingIntent.getActivity(context, 1, open, flags);
        PendingIntent silenceAction = PendingIntent.getBroadcast(context, 1, silence, flags);
        return new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("Tiden är ute!")
                .setContentText("Händerna från paketen.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(true)
                .setTimeoutAfter(Rounds.RING_MS)
                .setContentIntent(openAction)
                .setFullScreenIntent(openAction, true)
                .addAction(new Notification.Action.Builder(null, "Tysta larm", silenceAction).build())
                .build();
    }
}
