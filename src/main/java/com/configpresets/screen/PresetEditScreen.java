package com.configpresets.screen;

import com.configpresets.CaptureHandler;
import com.configpresets.ConfigPresetsMod;
import com.configpresets.Preset;
import com.configpresets.PresetManager;
import com.configpresets.PresetToast;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Create / edit screen for a single {@link Preset}.
 *
 * <p>Left column: identity (name, description, folder) and tag management with a
 * 16-color Minecraft palette. Right column: per-category capture toggles, an
 * expandable per-mod config list, and the default / auto-save flags.</p>
 */
public class PresetEditScreen extends Screen {

    /** The 16 standard Minecraft dye-style colors, used for tag swatches. */
    private static final String[] PALETTE = {
            "#FFFFFF", "#9D9D97", "#474F52", "#1D1D21",
            "#B02E26", "#F9801D", "#FED83D", "#80C71F",
            "#5E7C16", "#169C9C", "#3AB3DA", "#3C44AA",
            "#8932B8", "#C74EBD", "#F38BAA", "#835432"
    };

    private final PresetScreen parent;
    private final Preset preset;
    private final boolean isNew;
    private final PresetManager pm = ConfigPresetsMod.getPresetManager();

    // Working copy of tags (committed on Save).
    private final List<Preset.Tag> workingTags = new ArrayList<>();

    // Identity widgets.
    private TextFieldWidget nameField;
    private TextFieldWidget descField;
    private TextFieldWidget newFolderField;
    private TextFieldWidget tagNameField;
    private TextFieldWidget tagHexField;

    // Folder cycling.
    private final List<String> folderOptions = new ArrayList<>(); // entry 0 == null (root)
    private int folderIndex = 0;
    private ButtonWidget folderButton;

    // Capture toggles.
    private CheckboxWidget cbVideo;
    private CheckboxWidget cbSound;
    private CheckboxWidget cbControls;
    private CheckboxWidget cbLanguage;
    private CheckboxWidget cbAccessibility;
    private CheckboxWidget cbChat;
    private CheckboxWidget cbResourcePacks;
    private CheckboxWidget cbModConfigs;
    private CheckboxWidget cbDefault;
    private CheckboxWidget cbAutoSave;

    private String selectedHex = "#FED83D";

    // Tag pill hit-boxes for click handling (recomputed each render).
    private final List<int[]> tagRemoveBoxes = new ArrayList<>(); // [x0,y0,x1,y1,index]

    // Per-mod config list (custom scroll list).
    private final List<String> modIds = new ArrayList<>();
    private final Map<String, Boolean> modToggles = new LinkedHashMap<>();
    private int modListX;
    private int modListY;
    private int modListW;
    private int modListH;
    private static final int MOD_ROW_H = 14;
    private int modScroll = 0;

    public PresetEditScreen(PresetScreen parent, Preset preset, boolean isNew) {
        super(Text.literal(isNew ? "Create Preset" : "Edit Preset"));
        this.parent = parent;
        this.preset = preset;
        this.isNew = isNew;
        if (preset.tags != null) {
            for (Preset.Tag t : preset.tags) {
                workingTags.add(new Preset.Tag(t.name, t.hexColor));
            }
        }
    }

    @Override
    protected void init() {
        int leftX = 20;
        int colW = Math.min(220, this.width / 2 - 30);

        // ---- Name ----
        nameField = new TextFieldWidget(this.textRenderer, leftX, 44, colW, 18, Text.literal("Name"));
        nameField.setMaxLength(64);
        nameField.setText(preset.name == null ? "" : preset.name);
        this.addDrawableChild(nameField);

        // ---- Description ----
        descField = new TextFieldWidget(this.textRenderer, leftX, 84, colW, 18, Text.literal("Description"));
        descField.setMaxLength(256);
        descField.setText(preset.description == null ? "" : preset.description);
        this.addDrawableChild(descField);

        // ---- Folder cycling ----
        folderOptions.clear();
        folderOptions.add(null); // root
        for (String f : pm.getFolders()) {
            folderOptions.add(f);
        }
        folderIndex = Math.max(0, folderOptions.indexOf(preset.folder));
        folderButton = ButtonWidget.builder(folderLabel(), b -> {
            folderIndex = (folderIndex + 1) % folderOptions.size();
            b.setMessage(folderLabel());
        }).dimensions(leftX, 124, colW, 18).build();
        this.addDrawableChild(folderButton);

        newFolderField = new TextFieldWidget(this.textRenderer, leftX, 146, colW, 18, Text.literal("New folder"));
        newFolderField.setMaxLength(48);
        newFolderField.setPlaceholder(Text.literal("\u2026or type a new folder").formatted(Formatting.DARK_GRAY));
        this.addDrawableChild(newFolderField);

        // ---- Tag add row ----
        tagNameField = new TextFieldWidget(this.textRenderer, leftX, 200, colW - 56, 18, Text.literal("Tag"));
        tagNameField.setMaxLength(24);
        tagNameField.setPlaceholder(Text.literal("Tag name").formatted(Formatting.DARK_GRAY));
        this.addDrawableChild(tagNameField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Add"), b -> addTag())
                .dimensions(leftX + colW - 52, 200, 52, 18).build());

        tagHexField = new TextFieldWidget(this.textRenderer, leftX, 246, 70, 16, Text.literal("Hex"));
        tagHexField.setMaxLength(7);
        tagHexField.setText(selectedHex);
        tagHexField.setChangedListener(s -> {
            if (s.matches("#?[0-9a-fA-F]{6}")) {
                selectedHex = s.startsWith("#") ? s : "#" + s;
            }
        });
        this.addDrawableChild(tagHexField);

        // ---- Right column: capture toggles ----
        int rx = this.width / 2 + 14;
        int ry = 44;
        int step = 20;
        cbVideo = addToggle(rx, ry, "Video", preset.saveVideo);
        cbSound = addToggle(rx, ry += step, "Sound", preset.saveSound);
        cbControls = addToggle(rx, ry += step, "Controls / Keybinds", preset.saveControls);
        cbLanguage = addToggle(rx, ry += step, "Language", preset.saveLanguage);
        cbAccessibility = addToggle(rx, ry += step, "Accessibility", preset.saveAccessibility);
        cbChat = addToggle(rx, ry += step, "Chat", preset.saveChat);
        cbResourcePacks = addToggle(rx, ry += step, "Resource packs", preset.saveResourcePacks);
        cbModConfigs = addToggle(rx, ry += step, "Mod configs", preset.saveModConfigs);

        // ---- Per-mod config list ----
        modIds.clear();
        modToggles.clear();
        try {
            for (String id : ConfigPresetsMod.getCaptureHandler().getAvailableModIds()) {
                modIds.add(id);
                boolean on = preset.perModConfigToggles.getOrDefault(id, Boolean.TRUE);
                modToggles.put(id, on);
            }
        } catch (Throwable t) {
            ConfigPresetsMod.LOGGER.warn("Could not list mod configs", t);
        }
        modListX = rx;
        modListY = ry += step + 16;
        modListW = Math.min(240, this.width - rx - 16);
        modListH = Math.max(40, this.height - modListY - 96);

        // ---- Flags ----
        int fy = this.height - 80;
        cbDefault = addToggle(rx, fy, "Apply on launch (default)", preset.isDefault);
        cbAutoSave = addToggle(rx, fy + 20, "Auto-save this preset on exit", preset.autoSaveOnExit);

        // If this is the default preset, lock all category checkboxes ON and
        // disable the default/auto-save toggles (they are always forced on).
        if (pm.isDefaultPreset(preset)) {
            cbVideo.active = false;
            cbSound.active = false;
            cbControls.active = false;
            cbLanguage.active = false;
            cbAccessibility.active = false;
            cbChat.active = false;
            cbResourcePacks.active = false;
            cbModConfigs.active = false;
            cbDefault.active = false;
            cbAutoSave.active = false;
        }

        // ---- Bottom buttons ----
        int cx = this.width / 2;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> save())
                .dimensions(cx - 154, this.height - 28, 150, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close())
                .dimensions(cx + 4, this.height - 28, 150, 20).build());
    }

    private CheckboxWidget addToggle(int x, int y, String label, boolean checked) {
        CheckboxWidget cb = CheckboxWidget.builder(Text.literal(label), this.textRenderer)
                .pos(x, y)
                .checked(checked)
                .build();
        this.addDrawableChild(cb);
        return cb;
    }

    private Text folderLabel() {
        String f = folderOptions.get(folderIndex);
        return Text.literal("Folder: " + (f == null ? "(root)" : f));
    }

    private void addTag() {
        String name = tagNameField.getText().trim();
        if (name.isEmpty()) {
            return;
        }
        for (Preset.Tag t : workingTags) {
            if (t.name != null && t.name.equalsIgnoreCase(name)) {
                return; // no duplicates
            }
        }
        workingTags.add(new Preset.Tag(name, selectedHex));
        tagNameField.setText("");
    }

    private void save() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            PresetToast.showInfo("Preset name cannot be empty");
            return;
        }

        // Resolve folder: a typed new folder overrides the cycling selection.
        String typedFolder = newFolderField.getText().trim();
        String folder = !typedFolder.isEmpty() ? typedFolder : folderOptions.get(folderIndex);
        if (folder != null && folder.isBlank()) {
            folder = null;
        }

        // If renaming, remove the old file first.
        String oldName = preset.name;

        preset.name = name;
        preset.description = descField.getText().trim();
        preset.folder = folder;
        preset.tags = new ArrayList<>(workingTags);

        preset.saveVideo = cbVideo.isChecked();
        preset.saveSound = cbSound.isChecked();
        preset.saveControls = cbControls.isChecked();
        preset.saveLanguage = cbLanguage.isChecked();
        preset.saveAccessibility = cbAccessibility.isChecked();
        preset.saveChat = cbChat.isChecked();
        preset.saveResourcePacks = cbResourcePacks.isChecked();
        preset.saveModConfigs = cbModConfigs.isChecked();

        preset.perModConfigToggles = new LinkedHashMap<>(modToggles);
        preset.autoSaveOnExit = cbAutoSave.isChecked();

        // If this is the default preset, re-enforce all constraints regardless
        // of widget state (the checkboxes are locked, but be defensive).
        if (pm.isDefaultPreset(preset)) {
            preset.enforceDefaultConstraints();
        }

        // Capture the current settings into the preset.
        try {
            ConfigPresetsMod.getCaptureHandler().capture(preset);
        } catch (Throwable t) {
            ConfigPresetsMod.LOGGER.error("Capture during save failed", t);
        }

        // Persist, handling renames cleanly.
        if (!isNew && oldName != null && !PresetManager.sanitize(oldName).equals(PresetManager.sanitize(name))) {
            pm.delete(oldName);
        }
        boolean ok = pm.save(preset);

        // Default flag is exclusive and managed by the manager.
        if (cbDefault.isChecked()) {
            pm.setDefault(preset);
        } else if (preset.isDefault && !pm.isDefaultPreset(preset)) {
            // Only clear the flag if this is NOT the built-in default preset.
            pm.clearDefault();
            preset.isDefault = false;
        }

        if (ok) {
            PresetToast.showInfo((isNew ? "Created: " : "Saved: ") + preset.name);
            parent.onEditorSaved();
            close();
        } else {
            PresetToast.showInfo("Failed to save preset");
        }
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // NOTE (1.21.11): do NOT call this.renderBackground(...) here.
        // Screen#render already renders the blurred background, and the
        // 1.21.11 render backend throws "Can only blur once per frame" if
        // the blur is applied twice. super.render(...) handles it.
        super.render(context, mouseX, mouseY, delta);

        context.drawTextWithShadow(this.textRenderer, this.title, 20, 20, 0xFFFFFFFF);

        int leftX = 20;
        context.drawText(this.textRenderer, Text.literal("Name").formatted(Formatting.GRAY), leftX, 34, 0xFFAAAAAA, false);
        context.drawText(this.textRenderer, Text.literal("Description").formatted(Formatting.GRAY), leftX, 74, 0xFFAAAAAA, false);
        context.drawText(this.textRenderer, Text.literal("Tags").formatted(Formatting.GRAY), leftX, 190, 0xFFAAAAAA, false);

        // Color palette swatches.
        drawPalette(context, leftX + 78, 244, mouseX, mouseY);

        // Existing tag pills (with remove boxes).
        drawTagPills(context, leftX, 268);

        // Right column header.
        int rx = this.width / 2 + 14;
        if (pm.isDefaultPreset(preset)) {
            context.drawText(this.textRenderer,
                    Text.literal("Include in preset: (all locked \u2014 Default)")
                            .formatted(Formatting.GOLD),
                    rx, 32, 0xFFFFD700, false);
        } else {
            context.drawText(this.textRenderer, Text.literal("Include in preset:").formatted(Formatting.GRAY),
                    rx, 32, 0xFFAAAAAA, false);
        }

        // Mod config sub-list (only meaningful when "Mod configs" is on).
        boolean modsActive = cbModConfigs != null && cbModConfigs.isChecked();
        context.drawText(this.textRenderer,
                Text.literal("Per-mod configs" + (modsActive ? "" : " (enable above)"))
                        .formatted(modsActive ? Formatting.WHITE : Formatting.DARK_GRAY),
                modListX, modListY - 11, modsActive ? 0xFFFFFFFF : 0xFF666666, false);
        context.fill(modListX, modListY, modListX + modListW, modListY + modListH,
                modsActive ? 0x66000000 : 0x44000000);

        if (modsActive) {
            if (modIds.isEmpty()) {
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal("No mod configs found").formatted(Formatting.DARK_GRAY),
                        modListX + modListW / 2, modListY + modListH / 2 - 4, 0xFF666666);
            } else {
                context.enableScissor(modListX, modListY, modListX + modListW, modListY + modListH);
                int y = modListY - modScroll;
                for (int i = 0; i < modIds.size(); i++) {
                    int rowTop = y + i * MOD_ROW_H;
                    if (rowTop + MOD_ROW_H >= modListY && rowTop <= modListY + modListH) {
                        String id = modIds.get(i);
                        boolean on = modToggles.getOrDefault(id, true);
                        context.drawText(this.textRenderer,
                                Text.literal((on ? "\u2611 " : "\u2610 ") + id)
                                        .formatted(on ? Formatting.WHITE : Formatting.GRAY),
                                modListX + 4, rowTop + 3, on ? 0xFFFFFFFF : 0xFF999999, false);
                    }
                }
                context.disableScissor();
            }
        }
    }

    private void drawPalette(DrawContext context, int x, int y, int mouseX, int mouseY) {
        int sw = 14;
        for (int i = 0; i < PALETTE.length; i++) {
            int col = i % 8;
            int row = i / 8;
            int sx = x + col * (sw + 2);
            int sy = y + row * (sw + 2);
            int color = 0xFF000000 | (PresetScreen.parseHex(PALETTE[i]) & 0xFFFFFF);
            context.fill(sx, sy, sx + sw, sy + sw, color);
            boolean sel = PALETTE[i].equalsIgnoreCase(selectedHex);
            if (sel) {
                context.fill(sx - 1, sy - 1, sx + sw + 1, sy, 0xFFFFFFFF);
                context.fill(sx - 1, sy + sw, sx + sw + 1, sy + sw + 1, 0xFFFFFFFF);
                context.fill(sx - 1, sy, sx, sy + sw, 0xFFFFFFFF);
                context.fill(sx + sw, sy, sx + sw + 1, sy + sw, 0xFFFFFFFF);
            }
        }
    }

    private void drawTagPills(DrawContext context, int x, int y) {
        tagRemoveBoxes.clear();
        int px = x;
        int py = y;
        for (int i = 0; i < workingTags.size(); i++) {
            Preset.Tag t = workingTags.get(i);
            String label = (t.name == null ? "" : t.name) + " \u2715";
            int w = this.textRenderer.getWidth(label) + 8;
            if (px + w > this.width / 2 - 10) {
                px = x;
                py += 14;
            }
            int color = PresetScreen.parseHex(t.hexColor);
            context.fill(px, py, px + w, py + 11, 0xCC000000 | (color & 0xFFFFFF));
            context.drawText(this.textRenderer, Text.literal(label),
                    px + 4, py + 2, PresetScreen.contrastText(color), false);
            tagRemoveBoxes.add(new int[]{px, py, px + w, py + 11, i});
            px += w + 4;
        }
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mx = (int) click.x();
        int my = (int) click.y();

        // Palette swatch click.
        int px = 20 + 78;
        int py = 244;
        int sw = 14;
        for (int i = 0; i < PALETTE.length; i++) {
            int col = i % 8;
            int row = i / 8;
            int sx = px + col * (sw + 2);
            int sy = py + row * (sw + 2);
            if (mx >= sx && mx <= sx + sw && my >= sy && my <= sy + sw) {
                selectedHex = PALETTE[i];
                if (tagHexField != null) {
                    tagHexField.setText(selectedHex);
                }
                return true;
            }
        }

        // Tag remove click.
        for (int[] box : tagRemoveBoxes) {
            if (mx >= box[0] && mx <= box[2] && my >= box[1] && my <= box[3]) {
                int idx = box[4];
                if (idx >= 0 && idx < workingTags.size()) {
                    workingTags.remove(idx);
                }
                return true;
            }
        }

        // Per-mod toggle click.
        if (cbModConfigs != null && cbModConfigs.isChecked()
                && mx >= modListX && mx <= modListX + modListW
                && my >= modListY && my <= modListY + modListH) {
            int rel = (int) (my - modListY + modScroll);
            int idx = rel / MOD_ROW_H;
            if (idx >= 0 && idx < modIds.size()) {
                String id = modIds.get(idx);
                modToggles.put(id, !modToggles.getOrDefault(id, true));
                return true;
            }
        }

        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= modListX && mouseX <= modListX + modListW
                && mouseY >= modListY && mouseY <= modListY + modListH) {
            int contentH = modIds.size() * MOD_ROW_H;
            int maxScroll = Math.max(0, contentH - modListH);
            modScroll = Math.max(0, Math.min((int) (modScroll - verticalAmount * 10), maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /** Escape closes the editor and returns directly to the PresetScreen. */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
