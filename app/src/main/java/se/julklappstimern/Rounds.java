package se.julklappstimern;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.UUID;

final class Rounds {
    static final String EXTRA_ROUND = "round";
    static final String ACTION_EXPIRE = "se.julklappstimern.EXPIRE";
    static final String ACTION_SILENCE = "se.julklappstimern.SILENCE";
    static final String ACTION_SHOW_ALARM = "se.julklappstimern.SHOW_ALARM";
    static final long RING_MS = 60_000L;

    enum Phase { SETUP, RUNNING, PAUSED, CONFIRM_RUNNING, CONFIRM_PAUSED, RINGING, FINISHED, ABORTED }

    record Round(Phase phase, String id, int drawnSeconds, long deadline, long remaining) {
        static final Round EMPTY = new Round(Phase.SETUP, "", 0, 0, 0);

        Round moveTo(Phase phase, long deadline, long remaining) {
            return new Round(phase, id, drawnSeconds, deadline, remaining);
        }

        boolean isDue(long now) {
            return phase == Phase.RUNNING && now >= deadline;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final AlarmManager alarmManager;
    private final int boot;

    Rounds(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("rounds", Context.MODE_PRIVATE);
        alarmManager = this.context.getSystemService(AlarmManager.class);
        boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    TimeRange range() {
        return new TimeRange(prefs.getInt("min", TimeRange.DEFAULT.min()),
                prefs.getInt("max", TimeRange.DEFAULT.max()));
    }

    void setRange(TimeRange range) {
        prefs.edit().putInt("min", range.min()).putInt("max", range.max()).apply();
    }

    Round current() {
        if (prefs.getInt("boot", -2) != boot) {
            return Round.EMPTY;
        }
        return new Round(Phase.valueOf(prefs.getString("phase", Phase.SETUP.name())),
                prefs.getString("id", ""), prefs.getInt("draw", 0),
                prefs.getLong("deadline", 0), prefs.getLong("remaining", 0));
    }

    void observe(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    void unobserve(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    boolean canSchedule() {
        return Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms();
    }

    void start() {
        if (current().phase() != Phase.SETUP) return;

        int seconds = range().draw();
        arm(new Round(Phase.RUNNING, UUID.randomUUID().toString(), seconds,
                SystemClock.elapsedRealtime() + seconds * 1000L, 0), Round.EMPTY);
    }

    void pause() {
        suspend(Phase.PAUSED);
    }

    void openStop() {
        Round round = refresh();
        if (round.phase() == Phase.PAUSED) {
            save(round.moveTo(Phase.CONFIRM_PAUSED, 0, round.remaining()));
        } else if (round.phase() == Phase.RUNNING) {
            suspend(Phase.CONFIRM_RUNNING);
        }
    }

    private void suspend(Phase next) {
        Round round = current();
        long remaining = round.deadline() - SystemClock.elapsedRealtime();
        if (round.phase() != Phase.RUNNING) return;
        if (remaining <= 0) {
            refresh();
            return;
        }
        save(round.moveTo(next, 0, remaining));
        cancelAlarm(round.id());
    }

    void resume() {
        Round round = current();
        if (round.phase() == Phase.PAUSED || round.phase() == Phase.CONFIRM_RUNNING) {
            arm(round.moveTo(Phase.RUNNING, SystemClock.elapsedRealtime() + round.remaining(), 0),
                    round.moveTo(Phase.PAUSED, 0, round.remaining()));
        }
    }

    void keepRound() {
        Round round = current();
        if (round.phase() == Phase.CONFIRM_PAUSED) {
            save(round.moveTo(Phase.PAUSED, 0, round.remaining()));
        } else if (round.phase() == Phase.CONFIRM_RUNNING) {
            resume();
        }
    }

    void abort() {
        Round round = current();
        if (round.phase() == Phase.CONFIRM_RUNNING || round.phase() == Phase.CONFIRM_PAUSED) {
            save(round.moveTo(Phase.ABORTED, 0, 0));
        }
    }

    void anotherRound() {
        Round round = current();
        if (round.phase() == Phase.FINISHED || round.phase() == Phase.ABORTED) save(Round.EMPTY);
    }

    Round refresh() {
        Round round = current();
        long now = SystemClock.elapsedRealtime();
        if (round.isDue(now)) {
            expire(round.id());
        } else if (round.phase() == Phase.RINGING && now >= round.deadline()) {
            silence(round.id());
        }
        return current();
    }

    void expire(String id) {
        Round round = current();
        long now = SystemClock.elapsedRealtime();
        if (!round.id().equals(id) || !round.isDue(now)) return;

        save(round.moveTo(Phase.RINGING, now + RING_MS, 0));
        cancelAlarm(id);
        Intent intent = new Intent(context, AlarmPlayer.class).putExtra(EXTRA_ROUND, id);
        try {
            context.startForegroundService(intent);
        } catch (IllegalStateException | SecurityException unavailable) {
            AlarmPlayer.postFallback(context, id);
        }
    }

    void silence(String id) {
        if (finish(id)) context.stopService(new Intent(context, AlarmPlayer.class));
    }

    boolean finish(String id) {
        Round round = current();
        if (round.phase() != Phase.RINGING || !round.id().equals(id)) return false;
        save(round.moveTo(Phase.FINISHED, 0, 0));
        context.getSystemService(android.app.NotificationManager.class).cancel(AlarmPlayer.NOTIFICATION_ID);
        return true;
    }

    private void arm(Round running, Round onFailure) {
        if (!canSchedule()) {
            save(onFailure);
            throw new SecurityException("Exact alarms unavailable");
        }

        save(running);
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    running.deadline(), alarmIntent(running.id(), PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (RuntimeException failure) {
            cancelAlarm(running.id());
            save(onFailure);
            throw failure;
        }
    }

    private void cancelAlarm(String id) {
        PendingIntent pending = alarmIntent(id, PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            alarmManager.cancel(pending);
            pending.cancel();
        }
    }

    private PendingIntent alarmIntent(String id, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class).setAction(ACTION_EXPIRE)
                .setData(Uri.fromParts("julklappstimern", id, null)).putExtra(EXTRA_ROUND, id);
        return PendingIntent.getBroadcast(context, 0, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private void save(Round round) {
        prefs.edit().putInt("boot", boot).putString("phase", round.phase().name())
                .putString("id", round.id()).putInt("draw", round.drawnSeconds())
                .putLong("deadline", round.deadline()).putLong("remaining", round.remaining()).commit();
    }
}
