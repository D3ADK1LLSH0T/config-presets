package com.configpresets.screen;

import com.configpresets.CaptureHandler;
import com.configpresets.ConfigPresetsMod;
import com.configpresets.Preset;
import com.configpresets.PresetManager;
import com.configpresets.PresetToast;
import com.configpresets.config.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Main preset-management screen.
 *
 * <p>Layout:</p>
 * <ul>
 *   <li>Top: title + search field.</li>
 *   <li>Left: folder tabs (All, Unfiled, then each folder).</li>
 *   <li>Center: scrollable list of presets (name, folder badge, tag pills,
 *       default star).</li>
 *   <li>Bottom: action buttons (Create, Apply, Edit, Delete, Duplicate,
 *       Export, Import).</li>
 * </ul>
 */
public class PresetScreen extends Screen {

    // Folder-tab sentinels.
    private static final String TAB_ALL = "\u0000ALL";
    private static final String TAB_UNFILED = "\u0000UNFILED";

    private final Screen parent;
    private final PresetManager pm = ConfigPresetsMod.getPresetManager();

    private EditBox searchField;
    private String currentTab = TAB_ALL;

    private List<Preset> all = new ArrayList<>();
    private List<Preset> filtered = new ArrayList<>();
    private int selectedIndex = -1;

    // List geometry / scrolling.
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private static final int ROW_H = 30;
    private int scroll = 0;

    // Search debounce: filtering is deferred a few client ticks after the last
    // keystroke so rapid typing does not re-filter the whole list every frame.
    private static final int SEARCH_DEBOUNCE_TICKS = 3;
    private int searchCooldown = -1;

    public PresetScreen(Screen parent) {
        super(Component.literal("Config Presets"));
        // Prevent screen-history stacking: if this screen was opened from
        // another Config Presets screen (e.g. via the Config <-> Presets
        // navigation), skip back to that screen's own parent so a single
        // Escape press exits the Config Presets UI directly instead of
        // walking back through every screen in the history.
        while (parent instanceof PresetScreen) {
            parent = ((PresetScreen) parent).parent;
        }
        this.parent = parent;
    }

    /** Escape always closes this screen and returns directly to the parent. */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    // ---------------------------------------------------------------------
    // Shared button placement helper (also used by the screen mixins)
    // ---------------------------------------------------------------------

    /**
     * Computes the {@code [x, y]} of the 20x20 preset button for the given
     * screen size and the configured {@link ModConfig.ButtonPosition}.
     *
     * @param pauseScreen whether this is the pause screen (affects the
     *                    NEXT_TO_BUTTONS anchor)
     */
    public static int[] computeButtonPosition(int width, int height, boolean pauseScreen) {
        ModConfig.ButtonPosition pos = ModConfig.get().buttonPosition;
        int margin = 6;
        int size = 20;
        return switch (pos) {
            case TOP_LEFT -> new int[]{margin, margin};
            case TOP_RIGHT -> new int[]{width - size - margin, margin};
            case BOTTOM_LEFT -> new int[]{margin, height - size - margin};
            case BOTTOM_RIGHT -> new int[]{width - size - margin, height - size - margin};
            case NEXT_TO_BUTTONS -> pauseScreen
                    // Next to the camera / screenshot button, to the right of
                    // the Advancements / Statistics row.
                    ? new int[]{width / 2 + 128, height / 4 + 32}
                    // To the right of the title-screen button column.
                    : new int[]{width / 2 + 104, height / 4 + 48};
        };
    }

    // ---------------------------------------------------------------------
    // Init
    // ---------------------------------------------------------------------

    @Override
    protected void init() {
        reload();

        // Search field (top).
        int searchW = Math.min(220, this.width - 130);
        this.searchField = new EditBox(this.font,
                this.width - searchW - 16, 22, searchW, 18, Component.literal("Search"));
        this.searchField.setHint(Component.literal("Search by name or tag\u2026").withStyle(ChatFormatting.DARK_GRAY));
        this.searchField.setResponder(s -> searchCooldown = SEARCH_DEBOUNCE_TICKS);
        this.addRenderableWidget(this.searchField);

        // Left folder tabs.
        int tabX = 12;
        int tabY = 50;
        int tabW = 96;
        addTab(tabX, tabY, tabW, "All", TAB_ALL);
        addTab(tabX, tabY + 24, tabW, "Unfiled", TAB_UNFILED);
        int ty = tabY + 48;
        for (String folder : pm.getFolders()) {
            addTab(tabX, ty, tabW, folder, folder);
            ty += 24;
            if (ty > this.height - 60) {
                break;
            }
        }

        // Center list geometry.
        listX = 120;
        listY = 50;
        listW = this.width - listX - 16;
        listH = this.height - listY - 40;

        // Bottom action buttons.
        int by = this.height - 30;
        int bw = 70;
        int gap = 4;
        int bx = listX;
        bx = addAction(bx, by, bw, "Create New", b -> openEditor(new Preset("New Preset"), true), true) + gap;
        bx = addAction(bx, by, 56, "Apply", b -> applySelected(), hasSelection()) + gap;
        bx = addAction(bx, by, 52, "Edit", b -> {
            Preset sel = selected();
            if (sel != null) {
                openEditor(sel, false);
            }
        }, hasSelection()) + gap;
        bx = addAction(bx, by, 60, "Delete", b -> deleteSelected(), hasSelection() && !pm.isDefaultPreset(selected())) + gap;
        bx = addAction(bx, by, 74, "Duplicate", b -> duplicateSelected(), hasSelection()) + gap;
        bx = addAction(bx, by, 60, "Export", b -> {
            Preset sel = selected();
            if (sel != null && this.minecraft != null) {
                this.minecraft.setScreen(new ExportScreen(this, sel));
            }
        }, hasSelection()) + gap;
        addAction(bx, by, 60, "Import", b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new ImportScreen(this));
            }
        }, true);

        // Close button (top-left).
        this.addRenderableWidget(Button.builder(Component.literal("\u2190 Back"), b -> onClose())
                .bounds(12, 18, 96, 20).build());

        // Config (settings) button (bottom-right) -> opens this mod's Cloth config screen.
        this.addRenderableWidget(Button.builder(Component.literal("Config"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(ModConfig.createConfigScreen(this));
            }
        }).bounds(this.width - 12 - 96, this.height - 30, 96, 20).build());
    }

    private void addTab(int x, int y, int w, String label, String tabId) {
        boolean activeTab = currentTab.equals(tabId);
        Component text = activeTab
                ? Component.literal("\u25B6 " + label).withStyle(ChatFormatting.YELLOW)
                : Component.literal(label);
        Button b = Button.builder(text, btn -> {
            currentTab = tabId;
            scroll = 0;
            selectedIndex = -1;
            rebuildWidgets();
        }).bounds(x, y, w, 20).build();
        b.active = !activeTab;
        this.addRenderableWidget(b);
    }

    private int addAction(int x, int y, int w, String label, Button.OnPress action, boolean enabled) {
        Button b = Button.builder(Component.literal(label), action).bounds(x, y, w, 20).build();
        b.active = enabled;
        this.addRenderableWidget(b);
        return x + w;
    }

    // ---------------------------------------------------------------------
    // Data
    // ---------------------------------------------------------------------

    private void reload() {
        all = pm.getAll();
        applyFilter();
    }

    /** Applies the debounced search filter once the cooldown elapses. */
    @Override
    public void tick() {
        super.tick();
        if (searchCooldown > 0) {
            searchCooldown--;
        } else if (searchCooldown == 0) {
            searchCooldown = -1;
            applyFilter();
            selectedIndex = -1;
        }
    }

    private void applyFilter() {
        String q = (searchField != null ? searchField.getValue() : "").trim().toLowerCase(Locale.ROOT);
        filtered = new ArrayList<>();
        for (Preset p : all) {
            if (!matchesTab(p)) {
                continue;
            }
            if (!q.isEmpty() && !matchesQuery(p, q)) {
                continue;
            }
            filtered.add(p);
        }
    }

    private boolean matchesTab(Preset p) {
        if (TAB_ALL.equals(currentTab)) {
            return true;
        }
        if (TAB_UNFILED.equals(currentTab)) {
            return p.folder == null || p.folder.isBlank();
        }
        return currentTab.equals(p.folder);
    }

    private boolean matchesQuery(Preset p, String q) {
        if (p.name != null && p.name.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (p.folder != null && p.folder.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        for (Preset.Tag t : p.tags) {
            if (t.name != null && t.name.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSelection() {
        return selectedIndex >= 0 && selectedIndex < filtered.size();
    }

    private Preset selected() {
        return hasSelection() ? filtered.get(selectedIndex) : null;
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    private void applySelected() {
        Preset sel = selected();
        if (sel == null) {
            return;
        }
        try {
            if (ModConfig.get().backupBeforeApply) {
                Preset backup = new Preset("~backup (auto)");
                ConfigPresetsMod.getCaptureHandler().capture(backup);
                pm.save(backup);
            }

            // If applying the default preset, auto-save current settings first.
            if (pm.isDefaultPreset(sel)) {
                ConfigPresetsMod.getCaptureHandler().capture(sel);
                pm.save(sel);
            }

            // For non-default presets, use the Default preset as a fallback for
            // any unchecked categories so no settings are ever left un-applied.
            Preset fallback = pm.isDefaultPreset(sel) ? null : pm.getDefault();
            CaptureHandler.RestoreResult result = ConfigPresetsMod.getCaptureHandler().restore(sel, fallback);

            if (result.resourcePacksChanged) {
                CaptureHandler.triggerResourceReload();
            }
            // If mod config files changed, those mods only pick up the new values on
            // the next launch, so offer to restart the game.
            if (result.modConfigsRestored() && ModConfig.get().showRestartWarning
                    && this.minecraft != null) {
                this.minecraft.setScreen(new RestartPromptScreen(this));
            } else {
                PresetToast.showApplied(sel.name);
            }
        } catch (Throwable t) {
            ConfigPresetsMod.LOGGER.error("Failed to apply preset {}", sel.name, t);
            PresetToast.showInfo("Failed to apply preset");
        }
    }

    private void deleteSelected() {
        Preset sel = selected();
        if (sel == null || this.minecraft == null || pm.isDefaultPreset(sel)) {
            return;
        }
        this.minecraft.setScreen(new ConfirmScreen(this,
                Component.literal("Delete preset \"" + sel.name + "\"?"),
                () -> {
                    pm.delete(sel.name);
                    selectedIndex = -1;
                    reload();
                }));
    }

    private void duplicateSelected() {
        Preset sel = selected();
        if (sel == null) {
            return;
        }
        Preset copy = pm.duplicate(sel);
        if (copy != null) {
            PresetToast.showInfo("Duplicated: " + copy.name);
        }
        reload();
    }

    private void openEditor(Preset preset, boolean isNew) {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new PresetEditScreen(this, preset, isNew));
        }
    }

    /** Called by the editor after a save so the list refreshes. */
    void onEditorSaved() {
        reload();
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // List panel background.
        graphics.fill(listX, listY, listX + listW, listY + listH, 0x66000000);
        graphics.fill(listX, listY, listX + listW, listY + 1, 0xFF3A3A4A);

        if (filtered.isEmpty()) {
            String msg = all.isEmpty()
                    ? "No presets yet \u2014 click \"Create New\""
                    : "No presets match this filter";
            graphics.centeredText(this.font, Component.literal(msg).withStyle(ChatFormatting.GRAY),
                    listX + listW / 2, listY + listH / 2 - 4, 0xFFAAAAAA);
        }

        // Virtual scrolling: compute the visible index window directly and draw
        // only those rows instead of iterating the entire list every frame.
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        int baseY = listY - scroll;
        int size = filtered.size();
        int first = Math.max(0, scroll / ROW_H);
        int last = Math.min(size - 1, (scroll + listH) / ROW_H);
        for (int i = first; i <= last; i++) {
            drawRow(graphics, filtered.get(i), i, baseY + i * ROW_H, mouseX, mouseY);
        }
        graphics.disableScissor();

        // Scrollbar.
        int contentH = filtered.size() * ROW_H;
        if (contentH > listH) {
            int barX = listX + listW - 4;
            int barH = Math.max(20, (int) ((float) listH * listH / contentH));
            int barY = listY + (int) ((float) scroll * (listH - barH) / (contentH - listH));
            graphics.fill(barX, listY, barX + 3, listY + listH, 0x33FFFFFF);
            graphics.fill(barX, barY, barX + 3, barY + barH, 0xAAFFFFFF);
        }
    }

    private void drawRow(GuiGraphicsExtractor graphics, Preset p, int index, int rowTop, int mouseX, int mouseY) {
        boolean isSelected = index == selectedIndex;
        boolean hovered = mouseX >= listX && mouseX <= listX + listW - 6
                && mouseY >= rowTop && mouseY < rowTop + ROW_H
                && mouseY >= listY && mouseY <= listY + listH;

        int bg = isSelected ? 0x803A6EA5 : (hovered ? 0x40FFFFFF : 0x22000000);
        graphics.fill(listX + 2, rowTop + 2, listX + listW - 6, rowTop + ROW_H - 2, bg);
        if (isSelected) {
            graphics.fill(listX + 2, rowTop + 2, listX + 4, rowTop + ROW_H - 2, 0xFF55AAFF);
        }

        int tx = listX + 10;
        int nameColor = 0xFFFFFFFF;
        String name = p.name == null ? "(unnamed)" : p.name;
        if (p.isDefault) {
            graphics.text(this.font, Component.literal("\u2605").withStyle(ChatFormatting.GOLD),
                    tx, rowTop + 6, 0xFFFFD700, false);
            tx += 10;
        }
        graphics.text(this.font, trim(name, 150), tx, rowTop + 6, nameColor, false);

        // Folder badge + tags on the second line.
        int sx = listX + 10;
        int sy = rowTop + 17;
        if (p.folder != null && !p.folder.isBlank()) {
            sx = drawPill(graphics, "\uD83D\uDCC1 " + p.folder, sx, sy, 0xFF4A4A5A, 0xFFFFFFFF);
        }
        for (Preset.Tag t : p.tags) {
            if (t.name == null || t.name.isBlank()) {
                continue;
            }
            int color = parseHex(t.hexColor);
            sx = drawPill(graphics, t.name, sx, sy, 0xCC000000 | (color & 0xFFFFFF), contrastText(color));
            if (sx > listX + listW - 40) {
                break;
            }
        }
    }

    /** Draws a small rounded pill and returns the next x position. */
    private int drawPill(GuiGraphicsExtractor graphics, String label, int x, int y, int bgColor, int textColor) {
        int w = this.font.width(label) + 8;
        graphics.fill(x, y, x + w, y + 11, bgColor);
        graphics.text(this.font, Component.literal(label), x + 4, y + 2, textColor, false);
        return x + w + 4;
    }

    private String trim(String s, int maxWidth) {
        if (this.font.width(s) <= maxWidth) {
            return s;
        }
        // Measure incrementally instead of re-measuring a growing concatenation
        // each iteration (Minecraft glyph widths are additive).
        int budget = maxWidth - this.font.width("\u2026");
        StringBuilder sb = new StringBuilder(s.length());
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            int cw = this.font.width(String.valueOf(s.charAt(i)));
            if (width + cw > budget) {
                break;
            }
            sb.append(s.charAt(i));
            width += cw;
        }
        return sb.append('\u2026').toString();
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (mouseX >= listX && mouseX <= listX + listW
                && mouseY >= listY && mouseY <= listY + listH) {
            int rel = (int) (mouseY - listY + scroll);
            int idx = rel / ROW_H;
            if (idx >= 0 && idx < filtered.size()) {
                selectedIndex = idx;
                rebuildWidgets();
                // Double-click applies.
                if (doubled) {
                    applySelected();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= listX && mouseX <= listX + listW
                && mouseY >= listY && mouseY <= listY + listH) {
            int contentH = filtered.size() * ROW_H;
            int maxScroll = Math.max(0, contentH - listH);
            scroll = clamp((int) (scroll - verticalAmount * 16), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    // ---------------------------------------------------------------------
    // Color helpers
    // ---------------------------------------------------------------------

    static int parseHex(String hex) {
        if (hex == null) {
            return 0xFFFFFF;
        }
        String h = hex.replace("#", "").trim();
        try {
            return Integer.parseInt(h, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    /** Chooses black or white text for readability against a background color. */
    static int contrastText(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.6 ? 0xFF000000 : 0xFFFFFFFF;
    }

    Screen getParent() {
        return parent;
    }
}
