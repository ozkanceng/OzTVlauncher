package io.github.ozkanceng.ozlauncher.mirror;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.github.ozkanceng.ozlauncher.R;
import io.github.ozkanceng.ozlauncher.ui.Ui;

/**
 * Fullscreen Screen Mirroring Hub for Android TV.
 * Provides live AirPlay receiver for iPhone/iPad/Mac and Google Cast integration for Android/PC.
 */
public final class MirroringHubActivity extends Activity implements SurfaceHolder.Callback, AirPlayServer.Listener {
    private static final String TV_NAME = "OzTV";

    private FrameLayout root;
    private LinearLayout guideContainer;
    private SurfaceView videoSurface;
    private AirPlayServer airPlayServer;
    private boolean isMirroringActive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        airPlayServer = new AirPlayServer(this);
        airPlayServer.setListener(this);

        buildLayout();
        wakeUpGoogleCast();
    }

    private void buildLayout() {
        root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xFF0B132B, 0xFF050814 });
        root.setBackground(bg);
        setContentView(root);

        // Hardware video surface (placed behind guide initially)
        videoSurface = new SurfaceView(this);
        videoSurface.setVisibility(View.GONE);
        videoSurface.getHolder().addCallback(this);
        root.addView(videoSurface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        guideContainer = new LinearLayout(this);
        guideContainer.setOrientation(LinearLayout.VERTICAL);
        guideContainer.setPadding(Ui.dp(this, 54), Ui.dp(this, 36), Ui.dp(this, 54), Ui.dp(this, 36));
        root.addView(guideContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Header with Back Hint
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        guideContainer.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(R.string.screen_mirroring);
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView hint = new TextView(this);
        hint.setText("ESC / Geri: Çıkış");
        hint.setTextColor(0xAAFFFFFF);
        hint.setTextSize(15);
        header.addView(hint);

        // Status Card
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setBackground(Ui.frostedDock(this, 18));
        statusRow.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = Ui.dp(this, 24);
        guideContainer.addView(statusRow, statusLp);

        TextView nameView = new TextView(this);
        nameView.setText("📺  " + TV_NAME);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(20);
        nameView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        statusRow.addView(nameView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        String ip = AirPlayServer.getLocalIpAddress();
        TextView ipView = new TextView(this);
        ipView.setText("Wi-Fi: " + ip);
        ipView.setTextColor(0xCC38BDF8);
        ipView.setTextSize(16);
        statusRow.addView(ipView);

        TextView readyBadge = new TextView(this);
        readyBadge.setText(" ● " + getString(R.string.mirror_ready));
        readyBadge.setTextColor(0xFF4ADE80);
        readyBadge.setTextSize(16);
        readyBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        readyBadge.setPadding(Ui.dp(this, 18), 0, 0, 0);
        statusRow.addView(readyBadge);

        // Instruction Cards Row
        LinearLayout cardsRow = new LinearLayout(this);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        cardsLp.topMargin = Ui.dp(this, 28);
        guideContainer.addView(cardsRow, cardsLp);

        // Apple Card
        View appleCard = makeGuideCard(
                "🍏  " + getString(R.string.mirror_guide_apple),
                "1. Ekranın sağ üstünden aşağı kaydırıp Denetim Merkezi'ni açın.\n" +
                "2. 'Ekran Yansıtma' (iki ekran) simgesine dokunun.\n" +
                "3. Listeden '" + TV_NAME + "' seçin.\n\n" +
                "✓ AirPlay 2 & 60 FPS Donanım Hızlandırma",
                0xFF0284C7);
        LinearLayout.LayoutParams cardLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        cardLp1.setMarginEnd(Ui.dp(this, 16));
        cardsRow.addView(appleCard, cardLp1);

        // Android & PC Card
        View androidCard = makeGuideCard(
                "🤖  " + getString(R.string.mirror_guide_android),
                "1. Hızlı Ayarlar panelinden 'Yayınla' veya 'Smart View' seçin.\n" +
                "2. Listeden '" + TV_NAME + "' seçin.\n" +
                "3. Veya Chrome menüsünden 'Yayınla...' → 'Masaüstünü Yayınla' seçin.\n\n" +
                "✓ Google Cast (Chromecast Built-in) Aktif",
                0xFF10B981);
        LinearLayout.LayoutParams cardLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        cardLp2.setMarginStart(Ui.dp(this, 16));
        cardsRow.addView(androidCard, cardLp2);
    }

    private View makeGuideCard(String title, String body, int accentColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x22FFFFFF, 0x0EFFFFFF });
        cardBg.setCornerRadius(Ui.dp(this, 20));
        cardBg.setStroke(Ui.dp(this, 1.5f), 0x33FFFFFF);
        card.setBackground(cardBg);
        card.setPadding(Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        card.addView(tvTitle);

        View divider = new View(this);
        divider.setBackgroundColor(accentColor);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 3));
        divLp.topMargin = Ui.dp(this, 10);
        divLp.bottomMargin = Ui.dp(this, 16);
        card.addView(divider, divLp);

        TextView tvBody = new TextView(this);
        tvBody.setText(body);
        tvBody.setTextColor(0xEEFFFFFF);
        tvBody.setTextSize(15);
        tvBody.setLineSpacing(Ui.dp(this, 4), 1.15f);
        card.addView(tvBody);

        return card;
    }

    private void wakeUpGoogleCast() {
        try {
            Intent castIntent = new Intent();
            castIntent.setComponent(new ComponentName(
                    "com.google.android.apps.mediashell",
                    "com.google.android.apps.mediashell.CastReceiverService"));
            startService(castIntent);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Ui.hideSystemUi(getWindow().getDecorView());
        airPlayServer.start(TV_NAME);
    }

    @Override
    protected void onStop() {
        airPlayServer.stop();
        super.onStop();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        airPlayServer.setSurface(holder.getSurface(), 1920, 1080);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        airPlayServer.setSurface(holder.getSurface(), width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        airPlayServer.setSurface(null, 0, 0);
    }

    @Override
    public void onMirroringStarted() {
        isMirroringActive = true;
        guideContainer.setVisibility(View.GONE);
        videoSurface.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Yansıtma başladı (Durdurmak için Geri tuşuna basın)", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onMirroringStopped() {
        isMirroringActive = false;
        videoSurface.setVisibility(View.GONE);
        guideContainer.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isMirroringActive) {
                onMirroringStopped();
                return true;
            }
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
