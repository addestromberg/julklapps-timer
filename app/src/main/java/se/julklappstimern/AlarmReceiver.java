package se.julklappstimern;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra(Rounds.EXTRA_ROUND);
        if (id == null) return;

        Rounds rounds = new Rounds(context);
        if (Rounds.ACTION_EXPIRE.equals(intent.getAction())) rounds.expire(id);
        if (Rounds.ACTION_SILENCE.equals(intent.getAction())) rounds.silence(id);
    }
}
