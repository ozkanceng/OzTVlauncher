package io.github.ozkanceng.ozlauncher.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;

public final class Ui {
    private Ui() { }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable rounded(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable tileBackground(Context context, ThemePalette palette,
                                                   int radiusDp, boolean focused) {
        GradientDrawable drawable = rounded(Color.TRANSPARENT, radiusDp, context);
        drawable.setStroke(0, Color.TRANSPARENT);
        return drawable;
    }

    /**
     * Frosted glassmorphism background for the top pinned apps dock.
     */
    public static Drawable frostedDock(Context context, float radiusDp) {
        GradientDrawable base = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x2AFFFFFF, 0x14FFFFFF });
        base.setCornerRadius(dp(context, radiusDp));
        base.setStroke(dp(context, 1.5f), 0x3EFFFFFF);
        return base;
    }

    /**
     * A cinematic TV card plate with hyper-prominent white glow outline on focus,
     * matching modern Android TV / Google TV reference interfaces.
     */
    public static Drawable tvIconPlate(Context context, ThemePalette palette, boolean focused) {
        int top = focused ? blend(palette.surface, Color.WHITE, 0.22f)
                : blend(palette.surface, Color.WHITE, 0.05f);
        int bottom = focused ? blend(palette.surface, palette.background, 0.15f)
                : blend(palette.surface, palette.background, 0.35f);

        if (focused) {
            // Multilayer glowing focus frame:
            // Layer 0: Outer soft halo glow
            GradientDrawable halo = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { 0x55FFFFFF, 0x33FFFFFF });
            halo.setCornerRadius(dp(context, 20));
            halo.setStroke(dp(context, 4), 0x66FFFFFF);

            // Layer 1: Base high-contrast card background with 3dp bright solid white border
            GradientDrawable base = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, new int[] { top, bottom });
            base.setCornerRadius(dp(context, 18));
            base.setStroke(dp(context, 3), 0xFFFFFFFF);

            // Layer 2: Subtle top-light gradient sheen
            GradientDrawable sheen = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { 0x33FFFFFF, 0x00FFFFFF });
            sheen.setCornerRadius(dp(context, 18));

            LayerDrawable layers = new LayerDrawable(new Drawable[] { halo, base, sheen });
            layers.setLayerInset(1, dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2));
            layers.setLayerInset(2, dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 30));
            return layers;
        } else {
            GradientDrawable base = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, new int[] { top, bottom });
            base.setCornerRadius(dp(context, 18));
            base.setStroke(dp(context, 1), 0x22FFFFFF);

            GradientDrawable sheen = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { 0x12FFFFFF, 0x00FFFFFF });
            sheen.setCornerRadius(dp(context, 18));

            LayerDrawable layers = new LayerDrawable(new Drawable[] { base, sheen });
            layers.setLayerInset(1, dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 24));
            return layers;
        }
    }

    private static int blend(int from, int to, float amount) {
        float inverse = 1f - amount;
        return Color.argb(Math.round(Color.alpha(from) * inverse + Color.alpha(to) * amount),
                Math.round(Color.red(from) * inverse + Color.red(to) * amount),
                Math.round(Color.green(from) * inverse + Color.green(to) * amount),
                Math.round(Color.blue(from) * inverse + Color.blue(to) * amount));
    }

    public static void hideSystemUi(View decor) {
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
