package io.github.ozkanceng.ozlauncher.model;

import android.content.ComponentName;
import android.content.pm.ResolveInfo;

import java.util.Objects;

/** Immutable description of an installed application, hardware TV input, or system action. */
public final class LaunchItem {
    public enum Kind { APP, TV_INPUT, ACTION }

    public final String id;
    public final String label;
    public final Kind kind;
    public final String packageName;
    public final ComponentName component;
    public final String inputId;
    public final ResolveInfo resolveInfo;

    private LaunchItem(String id, String label, Kind kind, String packageName,
                       ComponentName component, String inputId, ResolveInfo resolveInfo) {
        this.id = id;
        this.label = label;
        this.kind = kind;
        this.packageName = packageName;
        this.component = component;
        this.inputId = inputId;
        this.resolveInfo = resolveInfo;
    }

    public static LaunchItem app(String label, String packageName,
                                 ComponentName component, ResolveInfo resolveInfo) {
        return new LaunchItem("app:" + packageName, label, Kind.APP, packageName,
                component, null, resolveInfo);
    }

    public static LaunchItem input(String label, String inputId) {
        return new LaunchItem("input:" + inputId, label, Kind.TV_INPUT, null,
                null, inputId, null);
    }

    public static LaunchItem action(String id, String label) {
        return new LaunchItem("action:" + id, label, Kind.ACTION, null,
                null, null, null);
    }

    @Override public boolean equals(Object other) {
        return other instanceof LaunchItem && id.equals(((LaunchItem) other).id);
    }

    @Override public int hashCode() {
        return Objects.hash(id);
    }
}
