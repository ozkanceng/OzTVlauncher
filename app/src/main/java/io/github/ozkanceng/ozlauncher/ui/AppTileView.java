package io.github.ozkanceng.ozlauncher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.ozkanceng.ozlauncher.icons.IconRepository;
import io.github.ozkanceng.ozlauncher.model.LaunchItem;

public final class AppTileView extends LinearLayout {

    /**
     * Hardware-accelerated rounded card container that guarantees clean border radius
     * clipping across all Android TV GPU drivers, and draws an outer stroke on top
     * so it never gets obscured by child image views.
     */
    public static final class CardPlateView extends FrameLayout {
        private final Path clipPath = new Path();
        private final RectF bounds = new RectF();
        private final RectF strokeBounds = new RectF();
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cornerRadiusPx;
        private boolean isCardFocused;

        public CardPlateView(Context context) {
            super(context);
            setWillNotDraw(false);
            cornerRadiusPx = Ui.dp(context, 18);

            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadiusPx);
                }
            });
            setClipToOutline(true);
        }

        public void setCornerRadius(float radiusPx) {
            this.cornerRadiusPx = radiusPx;
            updatePath();
            invalidateOutline();
            invalidate();
        }

        public void setCardFocused(boolean focused) {
            if (this.isCardFocused != focused) {
                this.isCardFocused = focused;
                invalidate();
            }
        }

        private void updatePath() {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            bounds.set(0, 0, w, h);
            clipPath.reset();
            clipPath.addRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updatePath();
        }

        @Override
        public void draw(Canvas canvas) {
            // 1. Strictly clip canvas to rounded rectangle so no child image can bleed out
            int saveCount = canvas.save();
            canvas.clipPath(clipPath);
            super.draw(canvas);
            canvas.restoreToCount(saveCount);

            // 2. Draw border stroke ON TOP of children to ensure crisp, visible rounded outline
            float strokeW = Ui.dp(getContext(), isCardFocused ? 3.5f : 1.2f);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(strokeW);
            borderPaint.setColor(isCardFocused ? Color.WHITE : 0x38FFFFFF);

            float half = strokeW / 2f;
            strokeBounds.set(half, half, getWidth() - half, getHeight() - half);
            float strokeRadius = Math.max(0, cornerRadiusPx - half);
            canvas.drawRoundRect(strokeBounds, strokeRadius, strokeRadius, borderPaint);
        }
    }

    private final CardPlateView iconPlate;
    private final ImageView icon;
    private final TextView label;
    private LaunchItem item;
    private ThemePalette palette;
    private int radiusDp = 18;
    private boolean reducedMotion;

    public AppTileView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        int pad = Ui.dp(context, 4);
        setPadding(pad, pad, pad, Ui.dp(context, 6));

        iconPlate = new CardPlateView(context);
        addView(iconPlate, new LayoutParams(Ui.dp(context, 154), Ui.dp(context, 90)));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setImageDrawable(new ColorDrawable(Color.TRANSPARENT));
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER);
        iconPlate.addView(icon, iconLp);

        label = new TextView(context);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(Color.WHITE);
        label.setTextSize(15);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        label.setLetterSpacing(0.005f);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setAlpha(0.0f);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(context, 8);
        addView(label, lp);

        setOnFocusChangeListener((view, focused) -> updateFocus(focused));
    }

    public void style(ThemePalette palette, int radiusDp, boolean reducedMotion) {
        this.palette = palette;
        this.radiusDp = radiusDp > 0 ? radiusDp : 18;
        this.reducedMotion = reducedMotion;
        iconPlate.setCornerRadius(Ui.dp(getContext(), this.radiusDp));
        updateFocus(isFocused());
    }

    public void bind(LaunchItem item, IconRepository icons, int iconPx) {
        this.item = item;
        if (item == null) {
            setVisibility(INVISIBLE);
            setContentDescription(null);
            return;
        }
        setVisibility(VISIBLE);
        label.setText(item.label);
        setContentDescription(item.label);
        Bitmap cached = icons.cached(item.id);
        if (cached != null) icon.setImageBitmap(cached); else icon.setImageDrawable(new ColorDrawable(0x2238BDF8));
        icons.request(item, iconPx, (id, bitmap) -> {
            if (this.item != null && this.item.id.equals(id) && bitmap != null) icon.setImageBitmap(bitmap);
        });
    }

    public LaunchItem item() { return item; }

    private void updateFocus(boolean focused) {
        if (palette == null) return;
        setBackground(Ui.tileBackground(getContext(), palette, radiusDp, focused));
        iconPlate.setBackground(Ui.tvIconPlate(getContext(), palette, focused));
        iconPlate.setCardFocused(focused);
        label.setTextColor(Color.WHITE);

        if (focused) {
            bringToFront();
            if (getParent() instanceof ViewGroup) {
                ((ViewGroup) getParent()).invalidate();
            }
        }

        float tileScale = focused && !reducedMotion ? 1.14f : 1f;
        float plateScale = focused && !reducedMotion ? 1.05f : 1f;

        if (reducedMotion) {
            setScaleX(tileScale);
            setScaleY(tileScale);
            iconPlate.setScaleX(plateScale);
            iconPlate.setScaleY(plateScale);
            label.setAlpha(focused ? 1.0f : 0.0f);
        } else {
            animate().scaleX(tileScale).scaleY(tileScale)
                    .setDuration(170)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f))
                    .start();
            iconPlate.animate().scaleX(plateScale).scaleY(plateScale)
                    .setDuration(170)
                    .start();
            label.animate().alpha(focused ? 1.0f : 0.0f).setDuration(150).start();
        }

        iconPlate.setElevation(focused ? Ui.dp(getContext(), 24) : Ui.dp(getContext(), 2));
        setElevation(focused ? Ui.dp(getContext(), 20) : 0);
    }
}
