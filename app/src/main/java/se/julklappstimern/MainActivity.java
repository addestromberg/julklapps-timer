package se.julklappstimern;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int ASK_NOTIFICATIONS = 1;
    // Spoken digit cues in the bundled countdown recording, in milliseconds.
    private static final int THREE_CUE = 4140;
    private static final int TWO_CUE = 4880;
    private static final int ONE_CUE = 5710;

    private enum SoundStage { IDLE, COUNTDOWN, HEARD, HELP }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable update = () -> sync(false);
    private final Runnable countdownTick = this::updateCountdownNumber;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        handler.removeCallbacks(update);
        handler.post(update);
    };

    private Rounds rounds;
    private Ui ui;
    private FrameLayout root;
    private Rounds.Round rendered;
    private AlertDialog stopDialog;
    private SoundStage stage = SoundStage.IDLE;
    private String soundProblem;
    private MediaPlayer audio;
    private TextView countdownNumber;
    private View countdownSpeaker;
    private boolean visible;
    private boolean askingNotifications;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        rounds = new Rounds(this);
        ui = new Ui(this);
        setVolumeControlStream(AudioManager.STREAM_ALARM);
        AlarmPlayer.createChannels(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        setContentView(root);
        root.requestApplyInsets();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        rounds.observe(changes);
        sync(true);
    }

    @Override protected void onResume() {
        super.onResume();
        sync(false);
    }

    @Override protected void onStop() {
        visible = false;
        rounds.unobserve(changes);
        handler.removeCallbacks(update);
        if (stage == SoundStage.COUNTDOWN) {
            releaseAudio();
            stage = SoundStage.IDLE;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        releaseAudio();
        if (stopDialog != null) stopDialog.dismiss();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        sync(false);
    }

    private void sync(boolean redraw) {
        if (!visible || isFinishing() || isDestroyed()) return;
        Rounds.Round round = rounds.refresh();
        boolean lockScreenAlarm = round.phase() == Rounds.Phase.RINGING
                && Rounds.ACTION_SHOW_ALARM.equals(getIntent().getAction())
                && round.id().equals(getIntent().getStringExtra(Rounds.EXTRA_ROUND));
        showLockScreenAlarm(lockScreenAlarm);

        if (round.phase() == Rounds.Phase.RUNNING || round.phase() == Rounds.Phase.RINGING) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        if (redraw || !round.equals(rendered)) {
            boolean animate = rendered == null || rendered.phase() != Rounds.Phase.RINGING;
            rendered = round;
            render(round, animate);
        }

        handler.removeCallbacks(update);
        if (round.phase() == Rounds.Phase.RUNNING || round.phase() == Rounds.Phase.RINGING) {
            handler.postDelayed(update, Math.max(1, round.deadline() - SystemClock.elapsedRealtime()));
        }
    }

    @SuppressWarnings("deprecation")
    private void showLockScreenAlarm(boolean show) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(show);
            setTurnScreenOn(show);
        } else {
            int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            if (show) getWindow().addFlags(flags);
            else getWindow().clearFlags(flags);
        }
    }

    private void render(Rounds.Round round, boolean animate) {
        root.removeAllViews();
        countdownNumber = null;
        countdownSpeaker = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout pane = ui.column();
        pane.setPadding(ui.dp(24), ui.dp(15), ui.dp(24), ui.dp(19));
        scroll.addView(pane, new ScrollView.LayoutParams(-1, -2));
        appBar(pane);

        boolean confirming = round.phase() == Rounds.Phase.CONFIRM_RUNNING
                || round.phase() == Rounds.Phase.CONFIRM_PAUSED;
        if (!confirming && stopDialog != null) stopDialog.dismiss();

        switch (round.phase()) {
            case SETUP -> renderSetupStage(pane);
            case RUNNING, CONFIRM_RUNNING -> active(pane, false);
            case PAUSED, CONFIRM_PAUSED -> active(pane, true);
            case RINGING -> result(pane, round, true, false, animate);
            case FINISHED -> result(pane, round, false, false, false);
            case ABORTED -> result(pane, round, false, true, false);
        }
        if (confirming) showStopDialog();
    }

    private void appBar(LinearLayout pane) {
        LinearLayout bar = ui.row();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_gift);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.addView(icon, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));

        TextView name = ui.text(getString(R.string.app_name), 16, Ui.WHITE, 600);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2);
        layout.leftMargin = ui.dp(10);
        bar.addView(name, layout);
        pane.addView(bar);
    }

    private void renderSetupStage(LinearLayout pane) {
        switch (stage) {
            case IDLE -> setup(pane);
            case COUNTDOWN -> countdown(pane);
            case HEARD -> heard(pane);
            case HELP -> help(pane);
        }
    }

    private void setup(LinearLayout pane) {
        ui.stretch(pane, 32);
        FrameLayout headline = new FrameLayout(this);
        TextView title = ui.text("Ställ in\nrundan.", 58, Ui.WHITE, 750);
        title.setLetterSpacing(-.045f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        title.setLineSpacing(-ui.dp(2), 1f);
        headline.addView(title);

        ImageView gift = new ImageView(this);
        gift.setImageResource(R.drawable.ic_gift);
        gift.setRotation(12);
        gift.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams stamp = new FrameLayout.LayoutParams(ui.dp(69), ui.dp(69),
                Gravity.TOP | Gravity.END);
        stamp.rightMargin = ui.dp(8);
        headline.addView(gift, stamp);
        pane.addView(headline);
        ui.gap(pane, 18);
        pane.addView(ui.text("Vi lottar tiden. Tomten håller tyst.", 16, Ui.SOFT, 400));
        ui.stretch(pane, 29);
        pane.addView(ui.text("Välj tidsintervall", 16, Ui.WHITE, 600));
        ui.gap(pane, 12);
        LinearLayout range = ui.row();
        TimeRange time = rounds.range();
        range.addView(timeTile("Min", time.min(), true), new LinearLayout.LayoutParams(0, -2, 1));
        range.addView(new View(this), new LinearLayout.LayoutParams(ui.dp(12), 1));
        range.addView(timeTile("Max", time.max(), false), new LinearLayout.LayoutParams(0, -2, 1));
        pane.addView(range);
        ui.stretch(pane, 32);
        pane.addView(primary("Starta", this::beginCountdown));
        ui.gap(pane, 12);
        centeredText(pane, "Ett ljudtest körs före start", 13, Ui.SOFT, 400);
    }

    private LinearLayout timeTile(String label, int seconds, boolean minimum) {
        LinearLayout card = ui.column();
        card.setPadding(ui.dp(17), ui.dp(16), ui.dp(17), ui.dp(17));
        card.setBackground(ui.clickable(Ui.PANEL, 21, Ui.EDGE));
        card.setFocusable(true);
        card.setContentDescription(label + ", " + seconds / 60 + " minuter, " + seconds % 60 + " sekunder");
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setClassName(Button.class.getName());
            }
        });
        LinearLayout top = ui.row();
        TextView key = ui.text(label, 14, Ui.SOFT, 600);
        top.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(ui.text("⌄", 19, Ui.SOFT, 600));
        card.addView(top);
        ui.gap(card, 8);
        TextView value = ui.text(TimeRange.format(seconds), 36, Ui.WHITE, 720);
        value.setLetterSpacing(-.05f);
        card.addView(value);
        card.setOnClickListener(view -> DurationPicker.show(this, rounds.range(), minimum, selected -> {
            rounds.setRange(selected);
            sync(true);
        }));
        return card;
    }

    private void countdown(LinearLayout pane) {
        ui.stretch(pane, 34);
        FrameLayout art = new FrameLayout(this);
        countdownSpeaker = new GiftArtwork(this, GiftArtwork.Style.SOUND, false);
        art.addView(countdownSpeaker, new FrameLayout.LayoutParams(-1, -1));
        countdownNumber = ui.text("", 92, Ui.LIME, 750);
        countdownNumber.setGravity(Gravity.CENTER);
        countdownNumber.setBackground(ui.background(Ui.TILE, 86, Ui.EDGE));
        art.addView(countdownNumber, new FrameLayout.LayoutParams(-1, -1));
        ui.center(pane, art, 172, 172);
        updateCountdownNumber();
        ui.gap(pane, 30);
        status(pane, "Tomten testar", "Lyssna på nedräkningen.");
        ui.stretch(pane, 30);
        pane.addView(quiet("Avbryt", this::cancelSoundCheck));
    }

    private void heard(LinearLayout pane) {
        ui.stretch(pane, 32);
        ui.center(pane, new GiftArtwork(this, GiftArtwork.Style.SOUND, false), 145, 145);
        ui.gap(pane, 32);
        status(pane, "Hördes ljudet?", "Tryck ja så plingar rundan igång.");
        ui.stretch(pane, 35);
        pane.addView(primary("Ja, starta rundan", this::startRound));
        ui.gap(pane, 10);
        pane.addView(secondary("Nej", () -> soundHelp(null)));
    }

    private void help(LinearLayout pane) {
        ui.stretch(pane, 27);
        ui.center(pane, new GiftArtwork(this, GiftArtwork.Style.MUTED, false), 145, 145);
        ui.gap(pane, 30);
        status(pane, "Tyst som en tomte", soundProblem == null
                ? "Kontrollera larmvolymen och att Stör ej släpper igenom larm." : soundProblem);
        ui.stretch(pane, 27);
        pane.addView(primary("Testa igen", this::beginCountdown));
        ui.gap(pane, 10);
        pane.addView(secondary("Öppna ljudinställningar", () -> startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS))));
        ui.gap(pane, 4);
        pane.addView(quiet("Tillbaka", this::cancelSoundCheck));
    }

    private void active(LinearLayout pane, boolean paused) {
        ui.stretch(pane, 31);
        GiftArtwork.Style art = paused ? GiftArtwork.Style.PAUSED : GiftArtwork.Style.SEALED;
        ui.center(pane, new GiftArtwork(this, art, false), 196, 196);
        ui.gap(pane, 30);
        status(pane, paused ? "Pausad" : "Rundan pågår",
                paused ? "Pakettjuven får andas." : "Tiden är hemlig. Tomten skvallrar aldrig.");
        ui.stretch(pane, 42);
        pane.addView(primary(paused ? "Fortsätt" : "Pausa",
                paused ? () -> trySchedule(rounds::resume) : rounds::pause));
        ui.gap(pane, 7);
        pane.addView(ui.button("Stoppa rundan", Color.TRANSPARENT, Ui.ROSE, Color.TRANSPARENT, rounds::openStop));
    }

    private void result(LinearLayout pane, Rounds.Round round, boolean ringing, boolean aborted, boolean animate) {
        ui.stretch(pane, 21);
        ui.center(pane, new GiftArtwork(this, aborted ? GiftArtwork.Style.ABORTED : GiftArtwork.Style.OPEN,
                ringing && animate), 194, 194);
        ui.gap(pane, 23);
        status(pane, aborted ? "Rundan avbruten" : "Tiden är ute!",
                aborted ? "Paketen fick amnesti." : "Händerna från paketen.");
        ui.gap(pane, 29);
        LinearLayout time = ui.column();
        time.setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(14));
        time.setBackground(ui.background(Ui.PANEL, 20, Ui.EDGE));
        centeredText(time, "Lottad tid", 13, Ui.SOFT, 600);
        ui.gap(time, 3);
        centeredText(time, TimeRange.format(round.drawnSeconds()), 33, Ui.WHITE, 720);
        LinearLayout.LayoutParams block = new LinearLayout.LayoutParams(-1, -2);
        block.setMargins(ui.dp(36), 0, ui.dp(36), 0);
        pane.addView(time, block);
        ui.stretch(pane, 25);
        pane.addView(ringing
                ? ui.button("Tysta larm", Ui.ROSE, Ui.BERRY, Color.TRANSPARENT, () -> rounds.silence(round.id()))
                : primary("Ny runda", rounds::anotherRound));
    }

    private void status(LinearLayout pane, String title, String info) {
        TextView heading = centeredText(pane, title, 36, Ui.WHITE, 730);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        heading.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        heading.setLetterSpacing(-.035f);
        ui.gap(pane, 12);
        TextView detail = centeredText(pane, info, 15, Ui.SOFT, 400);
        detail.setLineSpacing(ui.dp(4), 1f);
    }

    private TextView centeredText(LinearLayout pane, String value, int size, int ink, int weight) {
        TextView text = ui.text(value, size, ink, weight);
        text.setGravity(Gravity.CENTER);
        pane.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private Button primary(String label, Runnable action) {
        return ui.button(label, Ui.LIME, Ui.INK, Color.TRANSPARENT, action);
    }

    private Button secondary(String label, Runnable action) {
        return ui.button(label, Ui.PANEL, Ui.WHITE, Ui.EDGE, action);
    }

    private Button quiet(String label, Runnable action) {
        return ui.button(label, Color.TRANSPARENT, Ui.SOFT, Color.TRANSPARENT, action);
    }

    private void showStopDialog() {
        if (stopDialog != null) return;
        stopDialog = new AlertDialog.Builder(this)
                .setTitle("Stoppa rundan?")
                .setMessage("Rundan avslutas och den hemliga tiden avslöjas.")
                .setNegativeButton("Behåll rundan", (dialog, button) -> trySchedule(rounds::keepRound))
                .setPositiveButton("Stoppa och visa tiden", (dialog, button) -> rounds.abort())
                .setOnCancelListener(dialog -> trySchedule(rounds::keepRound))
                .create();
        stopDialog.setOnDismissListener(dialog -> stopDialog = null);
        stopDialog.show();
        stopDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Ui.ROSE);
    }

    private void beginCountdown() {
        if (stage == SoundStage.COUNTDOWN || !ready()) return;
        releaseAudio();
        audio = AlarmAudio.create(this, R.raw.nedrakning);
        if (audio == null) {
            soundHelp("Nedräkningen kunde inte spelas upp.");
            return;
        }
        audio.setOnCompletionListener(player -> {
            releaseAudio();
            stage = SoundStage.HEARD;
            sync(true);
        });
        audio.setOnErrorListener((player, what, extra) -> {
            soundHelp("Nedräkningen kunde inte spelas upp.");
            return true;
        });
        try {
            audio.start();
            stage = SoundStage.COUNTDOWN;
            sync(true);
        } catch (IllegalStateException unavailable) {
            soundHelp("Nedräkningen kunde inte spelas upp.");
        }
    }

    private void updateCountdownNumber() {
        handler.removeCallbacks(countdownTick);
        if (stage != SoundStage.COUNTDOWN || audio == null || countdownNumber == null) return;
        int position = audio.getCurrentPosition();
        String number = position < THREE_CUE ? "" : position < TWO_CUE ? "3" : position < ONE_CUE ? "2" : "1";
        if (!number.contentEquals(countdownNumber.getText())) countdownNumber.setText(number);
        countdownNumber.setVisibility(number.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        countdownSpeaker.setVisibility(number.isEmpty() ? View.VISIBLE : View.INVISIBLE);
        handler.postDelayed(countdownTick, 60);
    }

    private void startRound() {
        if (stage != SoundStage.HEARD || !ready()) return;
        if (trySchedule(rounds::start) && rounds.current().phase() == Rounds.Phase.RUNNING) {
            stage = SoundStage.IDLE;
            releaseAudio();
            audio = AlarmAudio.create(this, R.raw.startpling);
            if (audio != null) {
                audio.setOnCompletionListener(player -> releaseAudio());
                audio.setOnErrorListener((player, what, extra) -> {
                    releaseAudio();
                    return true;
                });
                try {
                    audio.start();
                } catch (IllegalStateException unavailable) {
                    releaseAudio();
                }
            }
            sync(true);
        }
    }

    private boolean trySchedule(Runnable operation) {
        try {
            operation.run();
            return true;
        } catch (SecurityException unavailable) {
            showExactAlarmSettings();
            return false;
        } catch (IllegalStateException unavailable) {
            Toast.makeText(this, "Rundan kunde inte startas. Försök igen.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private boolean ready() {
        if (!rounds.canSchedule()) {
            showExactAlarmSettings();
            return false;
        }

        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (!notifications.areNotificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && (!getPreferences(MODE_PRIVATE).getBoolean("asked_notifications", false)
                        || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
                if (!askingNotifications) {
                    askingNotifications = true;
                    getPreferences(MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply();
                    requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, ASK_NOTIFICATIONS);
                }
            } else {
                showNotificationSettings(false);
            }
            return false;
        }

        NotificationChannel channel = notifications.getNotificationChannel(AlarmPlayer.CHANNEL);
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            showNotificationSettings(true);
            return false;
        }

        String problem = AlarmAudio.blockedReason(this);
        if (problem != null) {
            soundHelp(problem);
            return false;
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != ASK_NOTIFICATIONS) return;
        askingNotifications = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (stage == SoundStage.HEARD) startRound();
            else beginCountdown();
        } else {
            showNotificationSettings(false);
        }
    }

    private void showExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) return;
        new AlertDialog.Builder(this)
                .setTitle("Tillåt alarm och påminnelser")
                .setMessage("Julklappstimern behöver kunna larma i tid även när mobilen är låst.")
                .setNegativeButton("Avbryt", null)
                .setPositiveButton("Öppna inställningar", (dialog, button) ->
                        startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:" + getPackageName()))))
                .show();
    }

    private void showNotificationSettings(boolean channel) {
        new AlertDialog.Builder(this)
                .setTitle("Tillåt larmnotiser")
                .setMessage("Larmnotisen gör att du kan tysta larmet från låsskärmen.")
                .setNegativeButton("Avbryt", null)
                .setPositiveButton("Öppna inställningar", (dialog, button) ->
                        startActivity(new Intent(channel ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                                : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                                .putExtra(Settings.EXTRA_CHANNEL_ID, AlarmPlayer.CHANNEL)))
                .show();
    }

    private void soundHelp(String problem) {
        releaseAudio();
        soundProblem = problem;
        stage = SoundStage.HELP;
        sync(true);
    }

    private void cancelSoundCheck() {
        releaseAudio();
        stage = SoundStage.IDLE;
        sync(true);
    }

    private void releaseAudio() {
        handler.removeCallbacks(countdownTick);
        if (audio != null) {
            audio.release();
            audio = null;
        }
    }
}
