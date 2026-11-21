package com.jiezhimod.client.gui;

import com.jiezhimod.alarm.AlarmConfig;
import com.jiezhimod.block.TileAlarmConsole;
import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageAlarmConsole;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.text.TextFormatting;

import java.io.IOException;

/**
 * 警示灯控制台的配置界面（v4.5）。
 *
 * <h3>为什么没有「应用」按钮</h3>
 * 和灯圈控制台保持一致：改一下就存（输入停 {@link #AUTOSAVE_TICKS} 刻后发包，
 * 关界面时再兜一次）。玩家明确说过"无需应用，输入即保存"。
 *
 * <h3>颜色为什么给两套入口</h3>
 * <ul>
 *   <li>R/G/B 三个框：要精确某个色号时用；</li>
 *   <li>下面 16 个染料色块：平时随手点一下 —— 和"手里拿着染料右键灯"是同一套颜色，
 *       玩家不用记 RGB 也不用去背包里翻染料。</li>
 * </ul>
 */
public class GuiAlarmConsole extends GuiScreen {

    private static final int W = 248;
    private static final int H = 150;
    private static final int AUTOSAVE_TICKS = 4;

    private static final int ID_CLOSE = 900;
    private static final int ID_ON = 901;
    private static final int ID_OFF = 902;
    private static final int ID_RPM_BASE = 910;

    private static final int[] QUICK_RPM = {0, 10, 20, 40, 80};

    private final TileAlarmConsole tile;
    private final AlarmConfig edit = new AlarmConfig();

    private GuiTextField rf;
    private GuiTextField gf;
    private GuiTextField bf;
    private GuiTextField rpmf;

    /** 16 个染料色块的命中框 {x, y, w, h} */
    private final int[][] swatch = new int[16][4];

    private int guiLeft;
    private int guiTop;
    private boolean dirty;
    private int idle;
    private int savedFlash;

    public GuiAlarmConsole(TileAlarmConsole tile) {
        this.tile = tile;
        AlarmConfig src = tile == null ? new AlarmConfig() : tile.config();
        this.edit.color = src.color;
        this.edit.rpm = src.rpm;
        this.edit.on = src.on;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.guiLeft = (this.width - W) / 2;
        this.guiTop = (this.height - H) / 2;

        int x = this.guiLeft;
        int y = this.guiTop;

        this.rf = field(x + 12, y + 30, 34);
        this.gf = field(x + 52, y + 30, 34);
        this.bf = field(x + 92, y + 30, 34);
        this.rf.setText(String.valueOf((this.edit.color >> 16) & 0xFF));
        this.gf.setText(String.valueOf((this.edit.color >> 8) & 0xFF));
        this.bf.setText(String.valueOf(this.edit.color & 0xFF));

        this.rpmf = field(x + 176, y + 30, 40);
        this.rpmf.setText(String.valueOf(this.edit.rpm));

        // 染料色板：2 行 x 8 列
        for (int i = 0; i < 16; i++) {
            int col = i % 8;
            int row = i / 8;
            this.swatch[i][0] = x + 12 + col * 16;
            this.swatch[i][1] = y + 62 + row * 16;
            this.swatch[i][2] = 14;
            this.swatch[i][3] = 14;
        }

        this.buttonList.add(new GuiButton(ID_ON, x + 12, y + 104, 60, 18,
                this.edit.on ? TextFormatting.GREEN + "开" : "开"));
        this.buttonList.add(new GuiButton(ID_OFF, x + 76, y + 104, 60, 18,
                this.edit.on ? "关" : TextFormatting.YELLOW + "关"));
        this.buttonList.add(new GuiButton(ID_CLOSE, x + W - 76, y + H - 26, 64, 18,
                I18n.format("gui.jiezhimod.ring.close")));

        int bx = x + 176;
        for (int i = 0; i < QUICK_RPM.length; i++) {
            this.buttonList.add(new GuiButton(ID_RPM_BASE + i, bx + (i % 3) * 24,
                    y + 52 + (i / 3) * 20, 22, 18, String.valueOf(QUICK_RPM[i])));
        }
    }

    private GuiTextField field(int x, int y, int w) {
        GuiTextField f = new GuiTextField(0, this.fontRenderer, x, y, w, 16);
        f.setMaxStringLength(6);
        f.setEnableBackgroundDrawing(true);
        f.setTextColor(0xFFFFFF);
        f.setDisabledTextColour(0x777777);
        return f;
    }

    // ==================== 绘制 ====================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        int x = this.guiLeft;
        int y = this.guiTop;

        // 面板
        drawRect(x - 1, y - 1, x + W + 1, y + H + 1, 0xFF2A3138);
        drawRect(x, y, x + W, y + H, 0xF012171C);
        drawRect(x, y, x + W, y + 1, 0xFF3E4A55);

        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.alarm.title"), x + 10, y + 8, 0x9FE8FF);

        // ---- 颜色 ----
        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.alarm.rgb"), x + 12, y + 20, 0xC8D4DE);
        this.fontRenderer.drawString("R", x + 12, y + 34, 0xFF6B6B);
        this.fontRenderer.drawString("G", x + 52, y + 34, 0x6BFF8E);
        this.fontRenderer.drawString("B", x + 92, y + 34, 0x6BB8FF);
        // 预览色块
        drawRect(x + 132, y + 28, x + 164, y + 46, 0xFF000000 | (this.edit.color & 0xFFFFFF));
        drawRect(x + 132, y + 28, x + 164, y + 29, 0x88FFFFFF);

        this.rf.drawTextBox();
        this.gf.drawTextBox();
        this.bf.drawTextBox();

        // ---- 染料色板 ----
        for (int i = 0; i < 16; i++) {
            int sx = this.swatch[i][0];
            int sy = this.swatch[i][1];
            int color = EnumDyeColor.byDyeDamage(i).getColorValue();
            drawRect(sx, sy, sx + this.swatch[i][2], sy + this.swatch[i][3], 0xFF000000 | color);
            // 当前色加一圈白框
            if ((color & 0xFFFFFF) == (this.edit.color & 0xFFFFFF)) {
                drawRect(sx - 1, sy - 1, sx + this.swatch[i][2] + 1, sy, 0xFFFFFFFF);
                drawRect(sx - 1, sy + this.swatch[i][3], sx + this.swatch[i][2] + 1,
                        sy + this.swatch[i][3] + 1, 0xFFFFFFFF);
                drawRect(sx - 1, sy, sx, sy + this.swatch[i][3], 0xFFFFFFFF);
                drawRect(sx + this.swatch[i][2], sy, sx + this.swatch[i][2] + 1,
                        sy + this.swatch[i][3], 0xFFFFFFFF);
            }
        }

        // ---- 转速 ----
        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.alarm.rpm"), x + 176, y + 20, 0xC8D4DE);
        this.rpmf.drawTextBox();
        this.fontRenderer.drawString("rpm", x + 220, y + 34, 0x8A97A3);
        this.fontRenderer.drawString("0 = " + I18n.format("gui.jiezhimod.alarm.still"),
                x + 176, y + 96, 0x8A97A3);

        // ---- 底部 ----
        super.drawScreen(mouseX, mouseY, partialTicks);

        int bound = this.tile == null ? 0 : this.tile.members().size();
        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.alarm.bound") + " " + bound
                        + " / " + TileAlarmConsole.MAX_MEMBERS,
                x + 12, y + 126, 0x8A97A3);

        if (this.savedFlash > 0) {
            this.fontRenderer.drawString(TextFormatting.GREEN + I18n.format("gui.jiezhimod.ring.saved"),
                    x + 12, y + 138, 0x55FF88);
        }
    }

    // ==================== 交互 ====================

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == ID_CLOSE) {
            this.mc.displayGuiScreen(null);
            return;
        }
        if (button.id == ID_ON) {
            this.edit.on = true;
            this.touch();
            this.initGui();
            return;
        }
        if (button.id == ID_OFF) {
            this.edit.on = false;
            this.touch();
            this.initGui();
            return;
        }
        int idx = button.id - ID_RPM_BASE;
        if (idx >= 0 && idx < QUICK_RPM.length) {
            this.edit.rpm = QUICK_RPM[idx];
            this.rpmf.setText(String.valueOf(this.edit.rpm));
            this.touch();
            return;
        }
        super.actionPerformed(button);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.rf.mouseClicked(mouseX, mouseY, mouseButton);
        this.gf.mouseClicked(mouseX, mouseY, mouseButton);
        this.bf.mouseClicked(mouseX, mouseY, mouseButton);
        this.rpmf.mouseClicked(mouseX, mouseY, mouseButton);

        for (int i = 0; i < 16; i++) {
            int[] s = this.swatch[i];
            if (mouseX >= s[0] && mouseX < s[0] + s[2] && mouseY >= s[1] && mouseY < s[1] + s[3]) {
                this.edit.color = EnumDyeColor.byDyeDamage(i).getColorValue() & 0xFFFFFF;
                this.rf.setText(String.valueOf((this.edit.color >> 16) & 0xFF));
                this.gf.setText(String.valueOf((this.edit.color >> 8) & 0xFF));
                this.bf.setText(String.valueOf(this.edit.color & 0xFF));
                this.touch();
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        boolean typing = false;
        if (this.rf.isFocused()) {
            typing = this.rf.textboxKeyTyped(typedChar, keyCode);
        } else if (this.gf.isFocused()) {
            typing = this.gf.textboxKeyTyped(typedChar, keyCode);
        } else if (this.bf.isFocused()) {
            typing = this.bf.textboxKeyTyped(typedChar, keyCode);
        } else if (this.rpmf.isFocused()) {
            typing = this.rpmf.textboxKeyTyped(typedChar, keyCode);
        }
        if (typing) {
            this.readFields();
            this.touch();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /** 从四个输入框读回编辑副本（非法输入保持原值，不要把颜色跳成 0） */
    private void readFields() {
        int r = clampByte(parse(this.rf, (this.edit.color >> 16) & 0xFF));
        int g = clampByte(parse(this.gf, (this.edit.color >> 8) & 0xFF));
        int b = clampByte(parse(this.bf, this.edit.color & 0xFF));
        this.edit.color = (r << 16) | (g << 8) | b;
        this.edit.rpm = AlarmConfig.clampRpm(parse(this.rpmf, this.edit.rpm));
    }

    private static int parse(GuiTextField f, int fallback) {
        try {
            return Integer.parseInt(f.getText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ==================== 自动保存 ====================

    private void touch() {
        this.dirty = true;
        this.idle = 0;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.rf.updateCursorCounter();
        this.gf.updateCursorCounter();
        this.bf.updateCursorCounter();
        this.rpmf.updateCursorCounter();
        if (this.savedFlash > 0) {
            this.savedFlash--;
        }
        if (this.dirty && ++this.idle >= AUTOSAVE_TICKS) {
            this.send();
        }
    }

    private void send() {
        this.dirty = false;
        this.idle = 0;
        if (this.tile == null) {
            return;
        }
        AlarmConfig out = this.edit.copy();
        // 本地也套一份，免得"开了又关"时界面和实际不一致
        this.tile.config().color = out.color;
        this.tile.config().rpm = out.rpm;
        this.tile.config().on = out.on;
        ModNetwork.CHANNEL.sendToServer(
                new MessageAlarmConsole(this.tile.getPos(), out, true));
        this.savedFlash = 24;
    }

    @Override
    public void onGuiClosed() {
        if (this.dirty) {
            this.send();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
