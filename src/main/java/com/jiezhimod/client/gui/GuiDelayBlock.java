package com.jiezhimod.client.gui;

import com.jiezhimod.block.DelayLogic;
import com.jiezhimod.block.TileDelay;
import com.jiezhimod.client.util.GuiDraw;
import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageDelayConfig;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * 红石延时器的设置界面（右键方块打开）。
 *
 * 三排：延时时间（数值 + tick/秒/分 单位）、输出方向（前/左/右/三向）、实时状态。
 * 客户端改完立刻发 MessageDelayConfig 给服务端，服务端 clamp 后回推同步包。
 */
@SideOnly(Side.CLIENT)
public class GuiDelayBlock extends GuiScreen {

    private static final int PANEL_W = 236;
    private static final int PANEL_H = 196;
    private static final int BTN_H = 18;
    private static final int GAP = 4;
    private static final int MARGIN = 12;

    private static final int ID_MINUS10 = 0;
    private static final int ID_MINUS = 1;
    private static final int ID_PLUS = 2;
    private static final int ID_PLUS10 = 3;
    private static final int ID_UNIT = 10;
    private static final int ID_MODE = 20;
    private static final int ID_REDETECT = 25;
    private static final int ID_CLOSE = 30;

    private final TileDelay tile;
    private final BlockPos pos;

    private int value;
    private int unit;
    private int mode;

    private GuiTextField valueField;
    private GuiButton[] unitButtons = new GuiButton[3];
    private GuiButton[] modeButtons = new GuiButton[4];
    private String lastValidText = "";

    public GuiDelayBlock(TileDelay tile) {
        this.tile = tile;
        this.pos = tile.getPos();
        this.value = tile.getValue();
        this.unit = tile.getUnit();
        this.mode = tile.getMode();
        this.lastValidText = String.valueOf(this.value);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        int endX = panelX + PANEL_W - MARGIN;

        int delayY = panelY + 26;
        int unitY = panelY + 52;
        int modeY = panelY + 78;

        int delayStart = endX - (30 + GAP + 24 + GAP + 46 + GAP + 24 + GAP + 30);
        this.buttonList.add(new GuiButton(ID_MINUS10, delayStart, delayY, 30, BTN_H, "-10"));
        this.buttonList.add(new GuiButton(ID_MINUS, delayStart + 34, delayY, 24, BTN_H, "-"));
        this.valueField = new GuiTextField(0, this.fontRenderer, delayStart + 62, delayY + 1, 46, 16);
        this.valueField.setMaxStringLength(4);
        this.valueField.setText(String.valueOf(this.value));
        this.valueField.setFocused(true);
        this.buttonList.add(new GuiButton(ID_PLUS, delayStart + 112, delayY, 24, BTN_H, "+"));
        this.buttonList.add(new GuiButton(ID_PLUS10, delayStart + 140, delayY, 30, BTN_H, "+10"));

        int unitStart = endX - (54 * 3 + GAP * 2);
        for (int i = 0; i < 3; i++) {
            this.unitButtons[i] = new GuiButton(ID_UNIT + i, unitStart + i * (54 + GAP), unitY, 54, BTN_H, "");
            this.buttonList.add(this.unitButtons[i]);
        }

        int modeStart = endX - (40 * 4 + 3 * 3);
        for (int i = 0; i < 4; i++) {
            this.modeButtons[i] = new GuiButton(ID_MODE + i, modeStart + i * 43, modeY, 40, BTN_H, "");
            this.buttonList.add(this.modeButtons[i]);
        }

        // 重检方向：输入面锁死之后，挪动线路的玩家需要这个按钮重新锁一次
        this.buttonList.add(new GuiButton(ID_REDETECT, panelX + MARGIN, panelY + PANEL_H - 26,
                84, BTN_H, I18n.format("gui.jiezhimod.delay.redetect")));

        this.buttonList.add(new GuiButton(ID_CLOSE, this.width / 2 - 35, panelY + PANEL_H - 26, 70, BTN_H,
                I18n.format("gui.jiezhimod.delay.close")));

        refreshLabels();
    }

    /** 选中项加 §a 高亮 + 方括号，未选中保持朴素 */
    private void refreshLabels() {
        for (int i = 0; i < this.unitButtons.length; i++) {
            String name = I18n.format("gui.jiezhimod.delay.unit." + i);
            this.unitButtons[i].displayString = i == this.unit ? "\u00a7a[" + name + "]\u00a7r" : name;
        }
        for (int i = 0; i < this.modeButtons.length; i++) {
            String name = I18n.format("gui.jiezhimod.delay.mode." + i);
            this.modeButtons[i].displayString = i == this.mode ? "\u00a7a[" + name + "]\u00a7r" : name;
        }
    }

    @Override
    public void updateScreen() {
        this.valueField.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        commitField();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        int id = button.id;
        if (id == ID_CLOSE) {
            closeGui();
            return;
        }
        if (id == ID_REDETECT) {
            // 只发包，不改本地字段：方向由服务端重置后经同步包回推
            ModNetwork.CHANNEL.sendToServer(
                    new MessageDelayConfig(this.pos, this.value, this.unit, this.mode, true));
            return;
        }
        if (id == ID_MINUS10) {
            this.value = DelayLogic.clampValue(this.value - 10);
        } else if (id == ID_MINUS) {
            this.value = DelayLogic.clampValue(this.value - 1);
        } else if (id == ID_PLUS) {
            this.value = DelayLogic.clampValue(this.value + 1);
        } else if (id == ID_PLUS10) {
            this.value = DelayLogic.clampValue(this.value + 10);
        } else if (id >= ID_UNIT && id < ID_UNIT + 3) {
            this.unit = DelayLogic.clampUnit(id - ID_UNIT);
        } else if (id >= ID_MODE && id < ID_MODE + 4) {
            this.mode = DelayLogic.clampMode(id - ID_MODE);
        } else {
            return;
        }
        this.lastValidText = String.valueOf(this.value);
        if (this.valueField != null) {
            this.valueField.setText(this.lastValidText);
        }
        refreshLabels();
        push();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeGui();
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            commitField();
            return;
        }
        if (this.valueField != null && this.valueField.isFocused()
                && this.valueField.textboxKeyTyped(typedChar, keyCode)) {
            sanitizeField();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (this.valueField != null) {
            this.valueField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    /** 只允许数字：非数字直接剔掉，空串按 0 处理 */
    private void sanitizeField() {
        String raw = this.valueField.getText();
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        String clean = sb.toString();
        if (!clean.equals(raw)) {
            this.valueField.setText(clean);
        }
        this.lastValidText = clean;
        commitField();
    }

    private void commitField() {
        String text = this.valueField == null ? "" : this.valueField.getText();
        int parsed = 0;
        if (!text.isEmpty()) {
            try {
                parsed = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                parsed = this.value;
            }
        }
        int clamped = DelayLogic.clampValue(parsed);
        if (clamped != this.value) {
            this.value = clamped;
            push();
        }
        this.lastValidText = String.valueOf(this.value);
        if (this.valueField != null) {
            this.valueField.setText(this.lastValidText);
        }
    }

    private void push() {
        ModNetwork.CHANNEL.sendToServer(new MessageDelayConfig(this.pos, this.value, this.unit, this.mode));
    }

    private void closeGui() {
        commitField();
        if (this.mc.player != null) {
            this.mc.player.closeScreen();
        }
        this.mc.displayGuiScreen(null);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        GuiDraw.drawRoundedRect(panelX, panelY, PANEL_W, PANEL_H, 8.0F, 0xEE0A0A0F);

        this.drawCenteredString(this.fontRenderer, I18n.format("gui.jiezhimod.delay.title"),
                this.width / 2, panelY + 9, 0xFFFFFFFF);

        this.drawString(this.fontRenderer, I18n.format("gui.jiezhimod.delay.delay"),
                panelX + MARGIN, panelY + 32, 0xFFAAAAAA);
        this.drawString(this.fontRenderer, I18n.format("gui.jiezhimod.delay.unit"),
                panelX + MARGIN, panelY + 58, 0xFFAAAAAA);
        this.drawString(this.fontRenderer, I18n.format("gui.jiezhimod.delay.mode"),
                panelX + MARGIN, panelY + 84, 0xFFAAAAAA);

        int ticks = DelayLogic.toTicks(this.value, this.unit);
        this.drawString(this.fontRenderer,
                I18n.format("gui.jiezhimod.delay.actual", DelayLogic.formatTicks(ticks)),
                panelX + MARGIN, panelY + 104, 0xFFDDDDDD);

        long now = this.mc.world == null ? 0L : this.mc.world.getTotalWorldTime();
        int remain = this.tile.remainingTicks(now);
        this.drawString(this.fontRenderer,
                I18n.format("gui.jiezhimod.delay.status",
                        onOff(this.tile.isInputOn()), onOff(this.tile.isOutputOn()), remain),
                panelX + MARGIN, panelY + 116, 0xFFDDDDDD);

        this.drawString(this.fontRenderer,
                I18n.format("gui.jiezhimod.delay.hint"),
                panelX + MARGIN, panelY + 130, 0xFF666666);

        this.drawString(this.fontRenderer,
                I18n.format("gui.jiezhimod.delay.io.in", dirName(this.tile.getInputDir())),
                panelX + MARGIN, panelY + 140, 0xFFAAAAAA);
        this.drawString(this.fontRenderer,
                I18n.format("gui.jiezhimod.delay.io.out", outputNames(this.tile.outputDirs())),
                panelX + MARGIN, panelY + 151, 0xFFAAAAAA);

        this.drawString(this.fontRenderer,
                I18n.format("gui.jiezhimod.delay.hint2"),
                panelX + MARGIN, panelY + 165, 0xFF888888);

        if (this.valueField != null) {
            this.valueField.drawTextBox();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String onOff(boolean flag) {
        return I18n.format(flag ? "gui.jiezhimod.delay.on" : "gui.jiezhimod.delay.off");
    }

    /** 方向名走 lang：gui.jiezhimod.delay.dir.<key>；null 显示"无输入" */
    private String dirName(EnumFacing facing) {
        if (facing == null) {
            return I18n.format("gui.jiezhimod.delay.io.none");
        }
        return I18n.format("gui.jiezhimod.delay.dir." + DelayLogic.facingKey(facing));
    }

    /** 实际输出面列表，例如 "南 / 东"；没有输入时显示 - */
    private String outputNames(java.util.List<EnumFacing> dirs) {
        if (dirs == null || dirs.isEmpty()) {
            return I18n.format("gui.jiezhimod.delay.dir.none");
        }
        StringBuilder sb = new StringBuilder();
        for (EnumFacing f : dirs) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(dirName(f));
        }
        return sb.toString();
    }

    // ==================== 便于自测 ====================

    public int value() {
        return value;
    }

    public int unit() {
        return unit;
    }

    public int mode() {
        return mode;
    }

    public int delayTicks() {
        return DelayLogic.toTicks(this.value, this.unit);
    }
}
