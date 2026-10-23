package io.github.ozkanceng.ozlauncher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
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
    private final FrameLayout iconPlate;
    private final ImageView icon;
    private final TextView label;
    private LaunchItem item;
    private ThemePalette palette;
    private int radiusDp;
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

        iconPlate = new FrameLayout(context);
        final int plateRadius = Ui.dp(context, 18);
        iconPlate.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), plateRadius);
            }
        });
        iconPlate.setClipToOutline(true);
        addView(iconPlate, new LayoutParams(Ui.dp(context, 150), Ui.dp(context, 88)));

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
        this.radiusDp = radiusDp;
        this.reducedMotion = reducedMotion;
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
