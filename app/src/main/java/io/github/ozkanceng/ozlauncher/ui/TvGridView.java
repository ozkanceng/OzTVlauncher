package io.github.ozkanceng.ozlauncher.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import io.github.ozkanceng.ozlauncher.icons.IconRepository;
import io.github.ozkanceng.ozlauncher.model.LaunchItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A fixed-window TV grid: only visible cells exist, and are rebound while scrolling. */
public final class TvGridView extends ViewGroup {
    public interface Listener {
        void onOpen(LaunchItem item);
        void onContext(LaunchItem item);
        void onEdge(boolean atTop);
    }

    private final ArrayList<AppTileView> cells = new ArrayList<>();
    private List<LaunchItem> items = Collections.emptyList();
    private IconRepository icons;
    private Listener listener;
    private int columns = 6;
    private int visibleRows = 3;
    private int topRow;
    private int selectedIndex;
    private int gapPx;
    private ThemePalette palette;
    private int radiusDp = 16;
    private boolean reducedMotion;

    public TvGridView(Context context) {
        super(context);
        setFocusable(false);
        setClipChildren(false);
        setClipToPadding(false);
        gapPx = Ui.dp(context, 14);
        setContentDescription(context.getString(io.github.ozkanceng.ozlauncher.R.string.cd_launcher_grid));
    }

    public void configure(int columns, int rows, ThemePalette palette, int radiusDp,
                          boolean reducedMotion, IconRepository icons, Listener listener) {
        this.columns = Math.max(1, columns);
        this.visibleRows = Math.max(1, rows);
        this.palette = palette;
        this.radiusDp = radiusDp;
        this.reducedMotion = reducedMotion;
        this.icons = icons;
        this.listener = listener;
        ensureCells();
        bindCells();
        requestLayout();
    }

    public void setItems(List<LaunchItem> items) {
        this.items = items == null ? Collections.emptyList() : new ArrayList<>(items);
        selectedIndex = Math.max(0, Math.min(selectedIndex, Math.max(0, this.items.size() - 1)));
        topRow = Math.max(0, Math.min(topRow, selectedIndex / columns));
        bindCells();
    }

    public List<LaunchItem> items() { return new ArrayList<>(items); }

    /** Frees all off-screen child views when an overlay is closed. */
    public void releaseCells() {
        items = Collections.emptyList();
        cells.clear();
        removeAllViews();
        topRow = 0;
        selectedIndex = 0;
    }

    public void focusSelected() {
        if (items.isEmpty()) return;
        int local = selectedIndex - topRow * columns;
        if (local >= 0 && local < cells.size()) cells.get(local).requestFocus();
    }

    private void ensureCells() {
        int needed = columns * visibleRows;
        while (cells.size() < needed) {
            AppTileView tile = new AppTileView(getContext());
            tile.setOnClickListener(view -> {
                LaunchItem item = tile.item();
                if (item != null && listener != null) listener.onOpen(item);
            });
            tile.setOnLongClickListener(view -> {
                LaunchItem item = tile.item();
                if (item != null && listener != null) listener.onContext(item);
                return true;
            });
            tile.setOnKeyListener((view, keyCode, event) -> handleKey(tile, keyCode, event));
            cells.add(tile);
            addView(tile);
        }
        while (cells.size() > needed) {
            AppTileView removed = cells.remove(cells.size() - 1);
            removeView(removed);
        }
        for (AppTileView tile : cells) tile.style(palette, radiusDp, reducedMotion);
    }

    private void bindCells() {
        if (icons == null) return;
        int base = topRow * columns;
        // Request a landscape bitmap so Android TV banners stay sharp and square fallback
        // icons can be centered without being stretched or cropped.
        int iconPx = Ui.dp(getContext(), columns >= 8 ? 160 : columns >= 6 ? 240 : 320);
        for (int i = 0; i < cells.size(); i++) {
            int index = base + i;
            cells.get(i).bind(index < items.size() ? items.get(index) : null, icons, iconPx);
        }
        invalidate();
    }

    private boolean handleKey(AppTileView tile, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return false;
        int local = cells.indexOf(tile);
        if (local < 0 || tile.item() == null) return false;
        selectedIndex = topRow * columns + local;
        GridNavigator.Direction direction;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: direction = GridNavigator.Direction.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: direction = GridNavigator.Direction.RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: direction = GridNavigator.Direction.UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: direction = GridNavigator.Direction.DOWN; break;
            default: return false;
        }
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
            if (direction == GridNavigator.Direction.LEFT) direction = GridNavigator.Direction.RIGHT;
            else if (direction == GridNavigator.Direction.RIGHT) direction = GridNavigator.Direction.LEFT;
        }
        int next = GridNavigator.next(selectedIndex, direction, columns, items.size());
        if (next == GridNavigator.EDGE_TOP) {
            if (listener != null) listener.onEdge(true);
            return true;
        }
        if (next == GridNavigator.EDGE_BOTTOM) {
            if (listener != null) listener.onEdge(false);
            return true;
        }
        if (next == selectedIndex) return true;
        selectedIndex = next;
        int selectedRow = selectedIndex / columns;
        if (selectedRow < topRow) topRow = selectedRow;
        else if (selectedRow >= topRow + visibleRows) topRow = selectedRow - visibleRows + 1;
        bindCells();
        post(this::focusSelected);
        return true;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int cellWidth = Math.max(1, (width - gapPx * (columns - 1)) / columns);
        int cellHeight = Math.max(1, (height - gapPx * (visibleRows - 1)) / visibleRows);
        int childWidth = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY);
        for (View child : cells) child.measure(childWidth, childHeight);
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;
        int cellWidth = Math.max(1, (width - gapPx * (columns - 1)) / columns);
        int cellHeight = Math.max(1, (height - gapPx * (visibleRows - 1)) / visibleRows);
        for (int i = 0; i < cells.size(); i++) {
            int row = i / columns;
            int col = i % columns;
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) col = columns - 1 - col;
            int left = col * (cellWidth + gapPx);
            int top = row * (cellHeight + gapPx);
            cells.get(i).layout(left, top, left + cellWidth, top + cellHeight);
        }
    }
}
