package io.github.ozkanceng.ozlauncher.catalog;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import io.github.ozkanceng.ozlauncher.model.LaunchItem;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads the local package catalog. It performs no network or background-service work. */
public final class CatalogLoader {
    private final Context context;
    private final PackageManager packageManager;

    public CatalogLoader(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = context.getPackageManager();
    }

    public List<LaunchItem> load() {
        Map<String, LaunchItem> byPackage = new LinkedHashMap<>();
        addActivities(byPackage, Intent.CATEGORY_LEANBACK_LAUNCHER);
        addActivities(byPackage, Intent.CATEGORY_LAUNCHER);

        ArrayList<LaunchItem> result = new ArrayList<>(byPackage.values());
        addTvInputs(result);
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        result.sort(Comparator.comparing(item -> item.label, collator));
        result.add(0, LaunchItem.action("mirror", context.getString(io.github.ozkanceng.ozlauncher.R.string.screen_mirroring)));
        return result;
    }

    private void addActivities(Map<String, LaunchItem> target, String category) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> matches = packageManager.queryIntentActivities(intent, 0);
        for (ResolveInfo info : matches) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || target.containsKey(pkg)) continue;
            CharSequence raw = info.loadLabel(packageManager);
            String label = raw == null ? pkg : raw.toString().trim();
            if (label.isEmpty()) label = pkg;
            ComponentName component = new ComponentName(pkg, info.activityInfo.name);
            target.put(pkg, LaunchItem.app(label, pkg, component, info));
        }
    }

    private void addTvInputs(List<LaunchItem> target) {
        TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        if (manager == null) return;
        try {
            ArrayList<TvInputInfo> passthrough = new ArrayList<>();
            ArrayList<TvInputInfo> hdmi = new ArrayList<>();
            for (TvInputInfo input : manager.getTvInputList()) {
                if (!input.isPassthroughInput()) continue;
                passthrough.add(input);
                if (input.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(input);
            }
            hdmi.sort(Comparator.comparingInt(input -> hardwareOrder(input.getId())));
            Map<String, Integer> hdmiPort = new LinkedHashMap<>();
            for (int i = 0; i < hdmi.size(); i++) hdmiPort.put(hdmi.get(i).getId(), i + 1);

            for (TvInputInfo input : passthrough) {
                CharSequence raw = input.loadCustomLabel(context);
                if (raw == null || raw.length() == 0) raw = input.loadLabel(context);
                String label = raw == null || raw.length() == 0
                        ? labelForType(input.getType()) : raw.toString();
                Integer port = hdmiPort.get(input.getId());
                if (input.getType() == TvInputInfo.TYPE_HDMI && port != null
                        && !label.matches(".*\\d.*")) {
                    label = "HDMI " + port;
                }
                target.add(LaunchItem.input(label, input.getId()));
            }
        } catch (RuntimeException ignored) {
            // Several vendor TV-input implementations throw while booting. Apps remain usable.
        }
    }

    static int hardwareOrder(String id) {
        if (id == null) return Integer.MAX_VALUE;
        int marker = id.lastIndexOf("/HW");
        if (marker < 0) return Integer.MAX_VALUE;
        try { return Integer.parseInt(id.substring(marker + 3)); }
        catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
    }

    private String labelForType(int type) {
        switch (type) {
            case TvInputInfo.TYPE_HDMI: return "HDMI";
            case TvInputInfo.TYPE_DISPLAY_PORT: return "DisplayPort";
            case TvInputInfo.TYPE_COMPONENT: return "Component";
            case TvInputInfo.TYPE_COMPOSITE: return "AV";
            case TvInputInfo.TYPE_VGA: return "VGA";
            default: return context.getString(io.github.ozkanceng.ozlauncher.R.string.tv_input);
        }
    }
}
