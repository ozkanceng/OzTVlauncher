package io.github.ozkanceng.ozlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.github.ozkanceng.ozlauncher.backup.BackupArchive;
import io.github.ozkanceng.ozlauncher.catalog.CatalogLoader;
import io.github.ozkanceng.ozlauncher.data.CatalogState;
import io.github.ozkanceng.ozlauncher.data.LauncherPreferences;
import io.github.ozkanceng.ozlauncher.icons.IconRepository;
import io.github.ozkanceng.ozlauncher.model.LaunchItem;
import io.github.ozkanceng.ozlauncher.ui.ThemePalette;
import io.github.ozkanceng.ozlauncher.ui.TvGridView;
import io.github.ozkanceng.ozlauncher.ui.Ui;
import io.github.ozkanceng.ozlauncher.wallpaper.WallpaperStore;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** OZLauncher's only activity. Feature logic lives in focused helper classes. */
public final class LauncherActivity extends Activity {
    private static final int PICK_ICON = 40;
    private static final int PICK_WALLPAPER = 41;
    private static final int CREATE_BACKUP = 42;
    private static final int OPEN_BACKUP = 43;
    private static final int[] SHORTCUT_KEYS = {
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_GREEN,
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS
    };

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService catalogExecutor = Executors.newSingleThreadExecutor();
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            long delay = 60_000L - System.currentTimeMillis() % 60_000L;
            clockHandler.postDelayed(this, delay);
        }
    };

    private LauncherPreferences preferences;
    private IconRepository icons;
    private WallpaperStore wallpapers;
    private CatalogLoader catalogLoader;
    private ThemePalette palette;

    private FrameLayout root;
    private ImageView wallpaperView;
    private View wallpaperShade;
    private TextView clockView;
    private TextView favoriteTitle;
    private TextView favoriteEmpty;
    private TvGridView favoriteGrid;
    private FrameLayout drawer;
    private TvGridView drawerGrid;
    private EditText search;
    private TextView drawerEmpty;
    private Button searchButton;
    private Button settingsButton;

    private List<LaunchItem> allItems = Collections.emptyList();
    private List<LaunchItem> visibleItems = Collections.emptyList();
    private final Map<String, LaunchItem> byId = new HashMap<>();
    private String pendingIconItemId;
    private boolean receiverRegistered;
    private boolean initialFocusApplied;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { reloadCatalog(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = new LauncherPreferences(this);
        icons = new IconRepository(this);
        wallpapers = new WallpaperStore(this);
        catalogLoader = new CatalogLoader(this);
        buildLayout();
        registerPackageReceiver();
        reloadCatalog();
        root.post(this::loadWallpaper);
    }

    @Override protected void onResume() {
        super.onResume();
        Ui.hideSystemUi(getWindow().getDecorView());
        clockHandler.removeCallbacks(clockTick);
        clockTick.run();
        reloadCatalog();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !initialFocusApplied && favoriteGrid != null && !favoriteGrid.items().isEmpty()) {
            favoriteGrid.postDelayed(() -> {
                favoriteGrid.focusSelected();
                initialFocusApplied = true;
            }, 120);
        }
    }

    @Override protected void onPause() {
        clockHandler.removeCallbacks(clockTick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(packageReceiver);
        catalogExecutor.shutdownNow();
        icons.shutdown();
        wallpapers.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        // HOME stays open when already on the home surface.
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_SEARCH) {
                openDrawer(true);
                return true;
            }
            String target = preferences.shortcuts().get(event.getKeyCode());
            if (target != null) {
                LaunchItem item = byId.get(target);
                if (item != null) { launch(item); return true; }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void buildLayout() {
        palette = ThemePalette.of(preferences.theme(), preferences.highContrast());
        root = new FrameLayout(this);
        root.setBackgroundColor(palette.background);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        setContentView(root);

        wallpaperView = new ImageView(this);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(wallpaperView, match());

        wallpaperShade = new View(this);
        wallpaperShade.setBackgroundColor(0x33000000);
        wallpaperShade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        wallpaperShade.setVisibility(View.GONE);
        root.addView(wallpaperShade, match());

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64), Gravity.TOP);
        topLp.setMargins(Ui.dp(this, 42), Ui.dp(this, 28), Ui.dp(this, 42), 0);
        root.addView(top, topLp);

        TextView brandView = new TextView(this);
        brandView.setText("OzTV");
        brandView.setTextColor(Color.WHITE);
        brandView.setTextSize(26);
        brandView.setTypeface(android.graphics.Typeface.create("sans-serif-black",
                android.graphics.Typeface.BOLD));
        brandView.setGravity(Gravity.CENTER_VERTICAL);
        brandView.setPadding(0, 0, Ui.dp(this, 16), 0);
        top.addView(brandView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View brandDivider = new View(this);
        brandDivider.setBackgroundColor(0x38FFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 1.5f), Ui.dp(this, 22));
        divLp.gravity = Gravity.CENTER_VERTICAL;
        divLp.setMarginEnd(Ui.dp(this, 16));
        top.addView(brandDivider, divLp);

        clockView = new TextView(this);
        clockView.setTextColor(palette.text);
        clockView.setTextSize(24);
        clockView.setTypeface(android.graphics.Typeface.create("sans-serif-light",
                android.graphics.Typeface.NORMAL));
        clockView.setGravity(Gravity.CENTER_VERTICAL);
        clockView.setContentDescription(getString(R.string.cd_clock));
        top.addView(clockView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        searchButton = pill("⌕", getString(R.string.cd_open_search));
        searchButton.setOnClickListener(view -> openDrawer(true));
        searchButton.setOnKeyListener((view, keyCode, event) -> focusFavoritesOnDown(keyCode, event));
        top.addView(searchButton, pillParams());

        settingsButton = pill("⚙", getString(R.string.cd_open_settings));
        settingsButton.setOnClickListener(view -> showSettings());
        settingsButton.setOnKeyListener((view, keyCode, event) -> focusFavoritesOnDown(keyCode, event));
        LinearLayout.LayoutParams settingsLp = pillParams();
        settingsLp.setMarginStart(Ui.dp(this, 12));
        top.addView(settingsButton, settingsLp);

        favoriteTitle = new TextView(this);
        favoriteTitle.setText(R.string.home_favorites);
        favoriteTitle.setTextColor(palette.text);
        favoriteTitle.setTextSize(20);
        favoriteTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        titleLp.setMargins(Ui.dp(this, 54), 0, 0, Ui.dp(this, 228));
        root.addView(favoriteTitle, titleLp);

        favoriteGrid = new TvGridView(this);
        favoriteEmpty = new TextView(this);
        favoriteEmpty.setText(R.string.favorite_empty);
        favoriteEmpty.setTextColor(0xCCFFFFFF);
        favoriteEmpty.setTextSize(17);
        favoriteEmpty.setGravity(Gravity.CENTER);

        FrameLayout favoriteDock = new FrameLayout(this);
        favoriteDock.setBackground(Ui.frostedDock(this, 26));
        favoriteDock.setClipChildren(false);
        favoriteDock.setClipToPadding(false);
        int dockPadH = Ui.dp(this, 16);
        int dockPadV = Ui.dp(this, 10);
        favoriteDock.setPadding(dockPadH, dockPadV, dockPadH, dockPadV);
        FrameLayout.LayoutParams favoritesLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 168), Gravity.BOTTOM);
        favoritesLp.setMargins(Ui.dp(this, 50), 0, Ui.dp(this, 50), Ui.dp(this, 52));
        favoriteDock.addView(favoriteGrid, match());
        favoriteDock.addView(favoriteEmpty, match());
        root.addView(favoriteDock, favoritesLp);

        TextView hint = new TextView(this);
        hint.setText(R.string.open_drawer_hint);
        hint.setTextColor(0xAAFFFFFF);
        hint.setTextSize(13);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLp.bottomMargin = Ui.dp(this, 18);
        root.addView(hint, hintLp);

        buildDrawer();
        configureGrids();
    }

    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setVisibility(View.GONE);
        drawer.setBackgroundColor(palette.background);
        root.addView(drawer, match());

        TextView title = new TextView(this);
        title.setText(R.string.all_apps);
        title.setTextColor(palette.text);
        title.setTextSize(26);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 58), Gravity.TOP | Gravity.START);
        titleLp.setMargins(Ui.dp(this, 48), Ui.dp(this, 24), 0, 0);
        drawer.addView(title, titleLp);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(R.string.search_apps);
        search.setTextColor(palette.text);
        search.setHintTextColor(0xAAFFFFFF);
        search.setTextSize(17);
        search.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        search.setBackground(Ui.rounded(palette.surface, 18, this));
        FrameLayout.LayoutParams searchLp = new FrameLayout.LayoutParams(Ui.dp(this, 360), Ui.dp(this, 52),
                Gravity.TOP | Gravity.END);
        searchLp.setMargins(0, Ui.dp(this, 26), Ui.dp(this, 48), 0);
        drawer.addView(search, searchLp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterDrawer(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        drawerGrid = new TvGridView(this);
        FrameLayout.LayoutParams gridLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gridLp.setMargins(Ui.dp(this, 48), Ui.dp(this, 100), Ui.dp(this, 48), Ui.dp(this, 42));
        drawer.addView(drawerGrid, gridLp);

        drawerEmpty = new TextView(this);
        drawerEmpty.setText(R.string.no_apps);
        drawerEmpty.setTextColor(0xCCFFFFFF);
        drawerEmpty.setTextSize(20);
        drawerEmpty.setGravity(Gravity.CENTER);
        drawer.addView(drawerEmpty, match());
    }

    private void configureGrids() {
        palette = ThemePalette.of(preferences.theme(), preferences.highContrast());
        root.setBackgroundColor(palette.background);
        drawer.setBackgroundColor(palette.background);
        favoriteTitle.setTextColor(palette.text);
        clockView.setTextColor(palette.text);
        search.setTextColor(palette.text);
        search.setBackground(Ui.rounded(palette.surface, 18, this));
        stylePill(searchButton);
        stylePill(settingsButton);
        favoriteGrid.configure(Math.min(6, preferences.columns()), 1, palette,
                preferences.radiusDp(), preferences.reducedMotion(), icons, homeListener);
        if (drawer.getVisibility() == View.VISIBLE) {
            drawerGrid.configure(preferences.columns(), 3, palette, preferences.radiusDp(),
                    preferences.reducedMotion(), icons, drawerListener);
        }
    }

    private final TvGridView.Listener homeListener = new TvGridView.Listener() {
        @Override public void onOpen(LaunchItem item) { launch(item); }
        @Override public void onContext(LaunchItem item) { showContext(item); }
        @Override public void onEdge(boolean atTop) {
            if (atTop) settingsButton.requestFocus(); else openDrawer(false);
        }
    };

    private final TvGridView.Listener drawerListener = new TvGridView.Listener() {
        @Override public void onOpen(LaunchItem item) { launch(item); }
        @Override public void onContext(LaunchItem item) { showContext(item); }
        @Override public void onEdge(boolean atTop) { if (atTop) closeDrawer(); }
    };

    private void reloadCatalog() {
        catalogExecutor.execute(() -> {
            List<LaunchItem> loaded = CatalogState.dedupeById(catalogLoader.load());
            runOnUiThread(() -> applyCatalog(loaded));
        });
    }

    private void applyCatalog(List<LaunchItem> loaded) {
        if (isFinishing() || isDestroyed()) return;
        allItems = CatalogState.applyOrder(loaded, preferences.order());
        byId.clear();
        for (LaunchItem item : allItems) byId.put(item.id, item);
        if (!preferences.favoritesInitialized()) {
            ArrayList<String> defaults = new ArrayList<>();
            for (LaunchItem item : allItems) {
                if (item.kind == LaunchItem.Kind.APP) defaults.add(item.id);
                if (defaults.size() == Math.min(6, preferences.columns())) break;
            }
            preferences.initializeFavorites(defaults);
        }
        visibleItems = CatalogState.visible(allItems, preferences.hidden());
        List<String> favIds = new ArrayList<>(preferences.favorites());
        if (!favIds.contains("action:mirror")) {
            favIds.add(0, "action:mirror");
            preferences.setFavorites(favIds);
        }
        List<LaunchItem> favorites = CatalogState.favorites(visibleItems, preferences.favorites());
        favoriteGrid.setItems(favorites);
        favoriteGrid.setVisibility(favorites.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        favoriteEmpty.setVisibility(favorites.isEmpty() ? View.VISIBLE : View.GONE);
        if (drawer.getVisibility() == View.VISIBLE) filterDrawer();
        if (drawer.getVisibility() != View.VISIBLE && !favorites.isEmpty()) favoriteGrid.postDelayed(favoriteGrid::focusSelected, 80);
    }

    private void filterDrawer() {
        if (drawerGrid == null) return;
        List<LaunchItem> filtered = CatalogState.search(visibleItems, search == null ? "" : search.getText().toString());
        drawerGrid.setItems(filtered);
        drawerEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        drawerGrid.setVisibility(filtered.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }

    private void openDrawer(boolean focusSearch) {
        drawer.setVisibility(View.VISIBLE);
        drawerGrid.configure(preferences.columns(), 3, palette, preferences.radiusDp(),
                preferences.reducedMotion(), icons, drawerListener);
        filterDrawer();
        if (focusSearch) {
            search.requestFocus();
            search.postDelayed(() -> {
                InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (manager != null) manager.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            }, 120);
        } else drawerGrid.post(drawerGrid::focusSelected);
    }

    private void closeDrawer() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.setText("");
        drawer.setVisibility(View.GONE);
        drawerGrid.releaseCells();
        favoriteGrid.post(favoriteGrid::focusSelected);
    }

    private void launch(LaunchItem item) {
        try {
            if (item.kind == LaunchItem.Kind.ACTION) {
                if ("action:mirror".equals(item.id)) {
                    Intent intent = new Intent(this, io.github.ozkanceng.ozlauncher.mirror.MirroringHubActivity.class);
                    startActivity(intent);
                    if (preferences.reducedMotion()) overridePendingTransition(0, 0);
                }
                return;
            }
            Intent intent;
            if (item.kind == LaunchItem.Kind.TV_INPUT) {
                intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(item.inputId));
            } else {
                intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(item.component);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
            if (preferences.reducedMotion()) overridePendingTransition(0, 0);
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void showContext(LaunchItem item) {
        boolean favorite = preferences.favorites().contains(item.id);
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        labels.add(getString(favorite ? R.string.remove_favorite : R.string.add_favorite));
        actions.add(() -> toggleFavorite(item.id));
        labels.add(getString(R.string.move_earlier)); actions.add(() -> move(item.id, -1));
        labels.add(getString(R.string.move_later)); actions.add(() -> move(item.id, 1));
        labels.add(getString(R.string.hide_app)); actions.add(() -> hide(item.id));
        if (item.kind == LaunchItem.Kind.APP) {
            labels.add(getString(R.string.custom_icon)); actions.add(() -> pickIcon(item.id));
            if (icons.customFiles().containsKey(item.id)) {
                labels.add(getString(R.string.reset_icon)); actions.add(() -> { icons.resetCustom(item.id); applyCatalog(allItems); });
            }
            labels.add(getString(R.string.app_info)); actions.add(() -> openAppInfo(item.packageName));
            labels.add(getString(R.string.uninstall)); actions.add(() -> uninstall(item.packageName));
        }
        new AlertDialog.Builder(this).setTitle(item.label)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void toggleFavorite(String id) {
        List<String> favorites = preferences.favorites();
        if (favorites.contains(id)) favorites.remove(id); else favorites.add(id);
        preferences.setFavorites(favorites);
        applyCatalog(allItems);
    }

    private void move(String id, int delta) {
        ArrayList<String> order = new ArrayList<>();
        for (LaunchItem item : allItems) order.add(item.id);
        int at = order.indexOf(id);
        int target = Math.max(0, Math.min(order.size() - 1, at + delta));
        if (at >= 0 && at != target) Collections.swap(order, at, target);
        preferences.setOrder(order);
        applyCatalog(allItems);
    }

    private void hide(String id) {
        Set<String> hidden = preferences.hidden();
        hidden.add(id);
        preferences.setHidden(hidden);
        List<String> favorites = preferences.favorites();
        favorites.remove(id);
        preferences.setFavorites(favorites);
        applyCatalog(allItems);
    }

    private void pickIcon(String id) {
        pendingIconItemId = id;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_ICON);
    }

    private void openAppInfo(String pkg) {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))); }
        catch (RuntimeException error) { Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show(); }
    }

    private void uninstall(String pkg) {
        try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))); }
        catch (RuntimeException error) { Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show(); }
    }

    private void showSettings() {
        String[] rows = {
                getString(R.string.theme), getString(R.string.columns), getString(R.string.corners),
                getString(R.string.clock_style), getString(R.string.wallpaper), getString(R.string.manage_hidden),
                getString(R.string.button_shortcuts), getString(R.string.accessibility), getString(R.string.backup),
                getString(R.string.restore), getString(R.string.system_settings), getString(R.string.about)
        };
        new AlertDialog.Builder(this).setTitle(R.string.settings).setItems(rows, (dialog, which) -> {
            switch (which) {
                case 0: chooseTheme(); break;
                case 1: chooseColumns(); break;
                case 2: chooseCorners(); break;
                case 3: chooseClock(); break;
                case 4: chooseWallpaperAction(); break;
                case 5: manageHidden(); break;
                case 6: chooseShortcutKey(); break;
                case 7: accessibilitySettings(); break;
                case 8: createBackup(); break;
                case 9: openBackup(); break;
                case 10: openSystemSettings(); break;
                case 11: about(); break;
                default: break;
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseTheme() {
        String[] names = {getString(R.string.palette_navy), getString(R.string.palette_lime),
                getString(R.string.palette_amber), getString(R.string.palette_violet),
                getString(R.string.palette_mint), getString(R.string.palette_mono)};
        new AlertDialog.Builder(this).setTitle(R.string.theme)
                .setSingleChoiceItems(names, preferences.theme(), (dialog, which) -> {
                    preferences.setTheme(which); dialog.dismiss(); rebuildStyle();
                }).show();
    }

    private void chooseColumns() {
        String[] values = {"5", "6", "7", "8"};
        new AlertDialog.Builder(this).setTitle(R.string.columns)
                .setSingleChoiceItems(values, preferences.columns() - 5, (dialog, which) -> {
                    preferences.setColumns(which + 5); dialog.dismiss(); configureGrids(); applyCatalog(allItems);
                }).show();
    }

    private void chooseCorners() {
        String[] values = {"0 dp", "8 dp", "16 dp", "24 dp"};
        int[] radii = {0, 8, 16, 24};
        int checked = Arrays.binarySearch(radii, preferences.radiusDp());
        new AlertDialog.Builder(this).setTitle(R.string.corners)
                .setSingleChoiceItems(values, Math.max(0, checked), (dialog, which) -> {
                    preferences.setRadiusDp(radii[which]); dialog.dismiss(); configureGrids(); applyCatalog(allItems);
                }).show();
    }

    private void chooseClock() {
        String[] values = {getString(R.string.full_clock), getString(R.string.time_only), getString(R.string.clock_off)};
        new AlertDialog.Builder(this).setTitle(R.string.clock_style)
                .setSingleChoiceItems(values, preferences.clockMode(), (dialog, which) -> {
                    preferences.setClockMode(which); dialog.dismiss(); updateClock();
                }).show();
    }

    private void chooseWallpaperAction() {
        String[] values = {getString(R.string.choose_wallpaper), getString(R.string.clear_wallpaper)};
        new AlertDialog.Builder(this).setTitle(R.string.wallpaper).setItems(values, (dialog, which) -> {
            if (which == 0) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, PICK_WALLPAPER);
            } else {
                wallpapers.clear(); wallpaperView.setImageDrawable(null); wallpaperShade.setVisibility(View.GONE);
            }
        }).show();
    }

    private void manageHidden() {
        Set<String> hidden = preferences.hidden();
        ArrayList<LaunchItem> hiddenItems = new ArrayList<>();
        for (LaunchItem item : allItems) if (hidden.contains(item.id)) hiddenItems.add(item);
        if (hiddenItems.isEmpty()) { Toast.makeText(this, R.string.hidden_empty, Toast.LENGTH_SHORT).show(); return; }
        String[] labels = new String[hiddenItems.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(R.string.unhide_app, hiddenItems.get(i).label);
        new AlertDialog.Builder(this).setTitle(R.string.manage_hidden).setItems(labels, (dialog, which) -> {
            Set<String> next = preferences.hidden(); next.remove(hiddenItems.get(which).id);
            preferences.setHidden(next); applyCatalog(allItems);
        }).show();
    }

    private void chooseShortcutKey() {
        String[] keys = {getString(R.string.shortcut_red), getString(R.string.shortcut_green),
                getString(R.string.shortcut_yellow), getString(R.string.shortcut_blue),
                getString(R.string.shortcut_menu), getString(R.string.shortcut_subtitle)};
        new AlertDialog.Builder(this).setTitle(R.string.button_shortcuts).setItems(keys,
                (dialog, which) -> chooseShortcutTarget(SHORTCUT_KEYS[which])).show();
    }

    private void chooseShortcutTarget(int keyCode) {
        String[] labels = new String[visibleItems.size() + 1];
        labels[0] = getString(R.string.not_assigned);
        for (int i = 0; i < visibleItems.size(); i++) labels[i + 1] = visibleItems.get(i).label;
        new AlertDialog.Builder(this).setTitle(R.string.choose_app).setItems(labels, (dialog, which) -> {
            preferences.setShortcut(keyCode, which == 0 ? null : visibleItems.get(which - 1).id);
            Toast.makeText(this, R.string.shortcut_saved, Toast.LENGTH_SHORT).show();
        }).show();
    }

    private void accessibilitySettings() {
        String[] rows = {getString(R.string.high_contrast), getString(R.string.reduced_motion)};
        boolean[] checked = {preferences.highContrast(), preferences.reducedMotion()};
        new AlertDialog.Builder(this).setTitle(R.string.accessibility)
                .setMultiChoiceItems(rows, checked, (dialog, which, value) -> checked[which] = value)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    preferences.setHighContrast(checked[0]); preferences.setReducedMotion(checked[1]); rebuildStyle();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void openSystemSettings() {
        try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        catch (RuntimeException error) { Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show(); }
    }

    private void about() {
        String version;
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { version = "1.0.0"; }
        String message = getString(R.string.version_label, version) + "\n\n"
                + getString(R.string.privacy_summary) + "\n"
                + getString(R.string.license_summary) + "\n\n" + getString(R.string.source_code);
        new AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void createBackup() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "OzTV-settings.ozbackup");
        startActivityForResult(intent, CREATE_BACKUP);
    }

    private void openBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, OPEN_BACKUP);
    }

    private void writeBackup(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException();
            Map<String, File> files = icons.customFiles();
            LinkedHashMap<String, String> names = new LinkedHashMap<>();
            for (String id : files.keySet()) names.put(id, "");
            JSONObject manifest = preferences.toJson(names);
            manifest.put("appVersion", "1.0.0");
            manifest.put("createdAtUtc", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date()));
            BackupArchive.write(out, manifest, files);
            Toast.makeText(this, R.string.backup_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreBackup(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException();
            BackupArchive.Restored restored = BackupArchive.read(in);
            if (!LauncherPreferences.isValidJson(restored.manifest)) throw new IllegalStateException();
            Map<String, File> previousFiles = icons.customFiles();
            LinkedHashMap<String, String> previousNames = new LinkedHashMap<>();
            for (String id : previousFiles.keySet()) previousNames.put(id, "");
            JSONObject previousSettings = preferences.toJson(previousNames);
            Map<String, byte[]> previousIcons = icons.customFileBytes();
            if (!preferences.applyJson(restored.manifest)) throw new IllegalStateException();
            if (!icons.replaceCustomFiles(restored.icons)) {
                preferences.applyJson(previousSettings);
                icons.replaceCustomFiles(previousIcons);
                throw new IllegalStateException();
            }
            rebuildStyle(); reloadCatalog(); loadWallpaper();
            Toast.makeText(this, R.string.restore_done, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_ICON && pendingIconItemId != null) {
            boolean saved = icons.saveCustom(pendingIconItemId, uri);
            pendingIconItemId = null;
            Toast.makeText(this, saved ? R.string.icon_saved : R.string.icon_failed, Toast.LENGTH_SHORT).show();
            applyCatalog(allItems);
        } else if (requestCode == PICK_WALLPAPER) {
            int width = Math.max(1, root.getWidth()); int height = Math.max(1, root.getHeight());
            wallpapers.save(uri, width, height, (bitmap, success) -> {
                if (success) { wallpaperView.setImageBitmap(bitmap); wallpaperShade.setVisibility(View.VISIBLE); }
                Toast.makeText(this, success ? R.string.wallpaper_saved : R.string.wallpaper_failed, Toast.LENGTH_SHORT).show();
            });
        } else if (requestCode == CREATE_BACKUP) writeBackup(uri);
        else if (requestCode == OPEN_BACKUP) restoreBackup(uri);
    }

    private void rebuildStyle() {
        palette = ThemePalette.of(preferences.theme(), preferences.highContrast());
        configureGrids();
        applyCatalog(allItems);
    }

    private void loadWallpaper() {
        if (!wallpapers.exists()) { wallpaperView.setImageDrawable(null); wallpaperShade.setVisibility(View.GONE); return; }
        wallpapers.load(Math.max(1, root.getWidth()), Math.max(1, root.getHeight()),
                (bitmap, success) -> {
                    if (success) { wallpaperView.setImageBitmap(bitmap); wallpaperShade.setVisibility(View.VISIBLE); }
                });
    }

    private void updateClock() {
        int mode = preferences.clockMode();
        if (mode == LauncherPreferences.CLOCK_OFF) { clockView.setVisibility(View.GONE); return; }
        clockView.setVisibility(View.VISIBLE);
        Date now = new Date();
        if (mode == LauncherPreferences.CLOCK_TIME) {
            clockView.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(now));
        } else {
            String date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(now);
            String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(now);
            clockView.setText(getString(R.string.date_time_format, date, time));
        }
    }

    private void registerPackageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packageReceiver, filter);
        receiverRegistered = true;
    }

    private Button pill(String text, String description) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(23);
        button.setTextColor(palette.text);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setPadding(0, 0, 0, 0);
        stylePill(button);
        button.setOnFocusChangeListener((view, focused) -> stylePill(button));
        return button;
    }

    private void stylePill(Button button) {
        if (button == null) return;
        button.setTextColor(button.isFocused() && palette.accent == Color.WHITE ? Color.BLACK : palette.text);
        button.setBackground(Ui.tileBackground(this, palette, 20, button.isFocused()));
    }

    private LinearLayout.LayoutParams pillParams() {
        return new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 48));
    }

    private boolean focusFavoritesOnDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0 && !favoriteGrid.items().isEmpty()) {
            favoriteGrid.focusSelected();
            return true;
        }
        return false;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
