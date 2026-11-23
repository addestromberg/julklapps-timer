package se.julklappstimern;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = 0xff101614;
    static final int PANEL = 0xff1b2520;
    static final int EDGE = 0xff34463b;
    static final int TILE = 0xff26352b;
    static final int SOFT = 0xffa9bbb0;
    static final int WHITE = 0xfff5f4ea;
    static final int LIME = 0xffd8eea1;
    static final int INK = 0xff172114;
    static final int ROSE = 0xffffb59f;
    static final int BERRY = 0xff392520;

    private final Context context;

    Ui(Context context) {
        this.context = context;
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    LinearLayout column() {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    LinearLayout row() {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    TextView text(CharSequence value, float size, int ink, int weight) {
        TextView label = new TextView(context);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(ink);
        label.setIncludeFontPadding(false);
        label.setTypeface(Build.VERSION.SDK_INT >= 28
                ? Typeface.create(Typeface.DEFAULT, weight, false)
                : Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL));
        return label;
    }

    Button button(String label, int fill, int ink, int border, Runnable action) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(ink);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setLetterSpacing(0);
        button.setStateListAnimator(null);
        button.setBackground(clickable(fill, 19, border));
        button.setMinHeight(dp(56));
        button.setPadding(dp(14), dp(12), dp(14), dp(12));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    GradientDrawable background(int fill, int radius, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (border != Color.TRANSPARENT) shape.setStroke(dp(1), border);
        return shape;
    }

    RippleDrawable clickable(int fill, int radius, int border) {
        return new RippleDrawable(ColorStateList.valueOf(0x28b6c5b5),
                background(fill, radius, border), background(Color.WHITE, radius, Color.TRANSPARENT));
    }

    void gap(LinearLayout parent, int height) {
        parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(height)));
    }

    void stretch(LinearLayout parent, int minimum) {
        View space = new View(context);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(minimum), 1));
    }

    void center(LinearLayout parent, View child, int width, int height) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                width < 0 ? width : dp(width), height < 0 ? height : dp(height));
        layout.gravity = Gravity.CENTER_HORIZONTAL;
        parent.addView(child, layout);
    }
}
