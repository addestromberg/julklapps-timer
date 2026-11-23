package se.julklappstimern;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

final class DurationPicker {
    private DurationPicker() {}

    static void show(Context context, TimeRange range, boolean editingMin, Consumer<TimeRange> onConfirm) {
        Ui ui = new Ui(context);
        Dialog dialog = new Dialog(context);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM);
        window.setDimAmount(.68f);

        LinearLayout panel = ui.column();
        panel.setBackground(ui.background(Ui.PANEL, 28, Ui.EDGE));
        panel.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(20));
        TextView title = ui.text(editingMin ? "Välj mintid" : "Välj maxtid", 26, Ui.WHITE, 700);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        panel.addView(title);
        ui.gap(panel, 20);

        int lower = range.lowerBound(editingMin);
        int upper = range.upperBound(editingMin);
        int selected = editingMin ? range.min() : range.max();
        NumberPicker minutes = picker(context, "Minuter");
        minutes.setMaxValue(upper / 60);
        minutes.setMinValue(lower / 60);
        minutes.setValue(selected / 60);
        minutes.setWrapSelectorWheel(false);
        NumberPicker seconds = picker(context, "Sekunder");
        setSeconds(seconds, minutes.getValue(), lower, upper, selected % 60 / TimeRange.STEP);
        minutes.setOnValueChangedListener((picker, oldValue, newValue) ->
                setSeconds(seconds, newValue, lower, upper, seconds.getValue()));

        LinearLayout wheels = ui.row();
        addWheel(ui, wheels, "Minuter", minutes);
        addWheel(ui, wheels, "Sekunder", seconds);
        panel.addView(wheels);
        ui.gap(panel, 16);
        panel.addView(ui.button("Klar", Ui.LIME, Ui.INK, Color.TRANSPARENT, () -> {
            int value = minutes.getValue() * 60 + seconds.getValue() * TimeRange.STEP;
            onConfirm.accept(range.with(editingMin, value));
            dialog.dismiss();
        }));
        ui.gap(panel, 4);
        panel.addView(ui.button("Avbryt", Color.TRANSPARENT, Ui.SOFT, Color.TRANSPARENT, dialog::dismiss));

        dialog.setContentView(panel);
        dialog.show();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static NumberPicker picker(Context context, String name) {
        NumberPicker picker = new NumberPicker(context);
        picker.setContentDescription(name);
        picker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        return picker;
    }

    private static void addWheel(Ui ui, LinearLayout row, String label, NumberPicker picker) {
        LinearLayout wheel = ui.column();
        wheel.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView caption = ui.text(label, 14, Ui.SOFT, 600);
        wheel.addView(caption);
        wheel.addView(picker, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(135)));
        row.addView(wheel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    private static void setSeconds(NumberPicker picker, int minute, int lower, int upper, int preferred) {
        int base = minute * 60;
        int first = Math.max(0, lower - base) / TimeRange.STEP;
        int last = Math.min(45, upper - base) / TimeRange.STEP;
        picker.setDisplayedValues(null);
        picker.setMinValue(0);
        picker.setMaxValue(last);
        picker.setMinValue(first);
        String[] labels = new String[last - first + 1];
        for (int step = first; step <= last; step++) {
            labels[step - first] = String.format(Locale.ROOT, "%02d", step * TimeRange.STEP);
        }
        picker.setDisplayedValues(labels);
        picker.setValue(Math.max(first, Math.min(last, preferred)));
        picker.setWrapSelectorWheel(false);
    }
}
