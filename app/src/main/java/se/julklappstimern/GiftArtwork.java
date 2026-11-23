package se.julklappstimern;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class GiftArtwork extends View {
    enum Style { SEALED, PAUSED, ABORTED, OPEN, SOUND, MUTED }

    private final Style style;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float progress = 1;
    private ValueAnimator animation;
    private final boolean animated;

    GiftArtwork(Context context, Style style, boolean animated) {
        super(context);
        this.style = style;
        this.animated = animated;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animated && ValueAnimator.areAnimatorsEnabled()) {
            animation = ValueAnimator.ofFloat(0, 1);
            animation.setDuration(680);
            animation.setInterpolator(new DecelerateInterpolator());
            animation.addUpdateListener(value -> {
                progress = (float) value.getAnimatedValue();
                invalidate();
            });
            animation.start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (animation != null) animation.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        canvas.save();
        canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
        canvas.scale(size / 200, size / 200);

        circle(canvas, 100, 100, 97, 0xff192720);
        outline(Ui.EDGE, 1);
        canvas.drawCircle(100, 100, 97, paint);
        canvas.drawCircle(100, 100, 80, paint);

        if (style == Style.SOUND || style == Style.MUTED) {
            drawSpeaker(canvas);
        } else {
            drawPackage(canvas);
        }
        canvas.restore();
    }

    private void drawSpeaker(Canvas canvas) {
        circle(canvas, 100, 100, 68, Ui.TILE);
        outline(style == Style.MUTED ? Ui.ROSE : Ui.LIME, 3.5f);
        path.reset();
        path.moveTo(65, 88);
        path.lineTo(80, 88);
        path.lineTo(99, 72);
        path.lineTo(99, 128);
        path.lineTo(80, 112);
        path.lineTo(65, 112);
        path.close();
        canvas.drawPath(path, paint);
        if (style == Style.MUTED) {
            canvas.drawLine(114, 90, 134, 110, paint);
            canvas.drawLine(134, 90, 114, 110, paint);
        } else {
            canvas.drawArc(89, 82, 126, 118, -57, 114, false, paint);
            canvas.drawArc(77, 71, 139, 129, -56, 112, false, paint);
        }
    }

    private void drawPackage(Canvas canvas) {
        boolean secret = style == Style.SEALED || style == Style.PAUSED;
        float open = style == Style.OPEN ? progress : 0;
        int ribbons = secret ? Ui.LIME : 0xffa95142;
        int box = secret ? 0xff789271 : 0xffd5e9a5;
        int lid = secret ? 0xff90aa83 : 0xffe1f2b5;

        canvas.save();
        if (style == Style.OPEN) {
            float shake = (float) (Math.sin(open * Math.PI * 5) * (1 - open) * 10);
            canvas.rotate(shake, 100, 115);
        }
        rect(canvas, 62, 149, 140, 157, 4, 0xff121b16);
        rect(canvas, 62, 94, 140, 148, 10, box);
        rect(canvas, 95, 95, 107, 148, 3, ribbons);

        canvas.save();
        canvas.translate(-4 * open, -15 * open);
        canvas.rotate(-14 * open, 100, 94);
        rect(canvas, 54, 80, 149, 104, 7, lid);
        rect(canvas, 95, 80, 107, 104, 3, ribbons);
        outline(ribbons, 4.7f);
        path.reset();
        path.moveTo(101, 80);
        path.cubicTo(79, 80, 78, 62, 89, 62);
        path.cubicTo(96, 62, 101, 80, 101, 80);
        path.cubicTo(106, 66, 117, 57, 122, 65);
        path.cubicTo(128, 76, 116, 82, 101, 80);
        canvas.drawPath(path, paint);
        canvas.restore();
        canvas.restore();

        spark(canvas, 32, 54, 6, 0xff769582);
        spark(canvas, 168, 147, 7, Ui.LIME);
        circle(canvas, 35, 149, 2, 0xff759884);
        circle(canvas, 168, 52, 2, Ui.ROSE);
        if (style == Style.OPEN && open > 0.25f) {
            spark(canvas, 177, 37, 8 * open, Ui.LIME);
            spark(canvas, 20, 115, 5 * open, Ui.ROSE);
            outline(ribbons, 2.5f);
            path.reset();
            path.moveTo(151, 69);
            path.cubicTo(168, 66, 157, 46, 174, 48);
            canvas.drawPath(path, paint);
        }
        if (secret) drawBadge(canvas);
    }

    private void drawBadge(Canvas canvas) {
        rect(canvas, 138, 137, 179, 178, 12, 0xff244034);
        outline(0xff486857, 1);
        canvas.drawRoundRect(138, 137, 179, 178, 12, 12, paint);

        outline(Ui.LIME, 1.7f);
        if (style == Style.PAUSED) {
            canvas.drawLine(155, 150, 155, 165, paint);
            canvas.drawLine(162, 150, 162, 165, paint);
        } else {
            canvas.drawRoundRect(152, 156, 165, 166, 2, 2, paint);
            canvas.drawArc(154, 149, 163, 159, 180, 180, false, paint);
            canvas.drawLine(154, 154, 154, 156, paint);
            canvas.drawLine(163, 154, 163, 156, paint);
        }
    }

    private void spark(Canvas canvas, float x, float y, float radius, int color) {
        outline(color, 1.25f);
        canvas.drawLine(x-radius, y, x+radius, y, paint);
        canvas.drawLine(x, y-radius, x, y+radius, paint);
    }

    private void circle(Canvas canvas, float x, float y, float radius, int color) {
        fill(color);
        canvas.drawCircle(x, y, radius, paint);
    }

    private void rect(Canvas canvas, float left, float top, float right, float bottom, float radius, int color) {
        fill(color);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void outline(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }
}
