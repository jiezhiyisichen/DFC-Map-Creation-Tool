package com.jiezhimod.client.gui;

import com.jiezhimod.block.ContainerRingConsole;
import com.jiezhimod.block.TileRingConsole;
import com.jiezhimod.client.util.GuiDraw;
import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageRingConsoleConfig;
import com.jiezhimod.ring.RingColor;
import com.jiezhimod.ring.RingConsoleConfig;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 灯圈控制台的配置界面（v4.4，2026-10 按实机反馈重做）。
 *
 * <h3>与上一版的区别（都是实机反馈直接提的）</h3>
 * <ol>
 *   <li><b>开启 / 关闭互斥</b>：两个同时选没有意义（后执行的把前一个覆盖掉），
 *       现在点一个会自动取消另一个；</li>
 *   <li><b>只显示选中的动作</b>：右列不再把四段配置全摊开，选了什么才出现什么；
 *       一段都没选时中间写一句提示。上一版四段挤在一起，标签直接压在输入框上；</li>
 *   <li><b>没有「应用」按钮</b>：改一下就存（输入停下 4 tick、或点按钮、或关界面时发包），
 *       底部只留「关闭」；</li>
 *   <li><b>预设色真的上色了</b>：上一版用 GuiButton 当色块，按钮底纹把颜色盖成灰，
 *       7 个格子全是空的。现在改成背景层自绘色块 + 自己做点击命中；</li>
 *   <li>预览色块放在「切换颜色至」那一行右边，随时看得到。</li>
 * </ol>
 *
 * <h3>为什么还是"整份配置"发给服务端</h3>
 * 自动保存只是"更早地发出去"，报文格式不变（{@link MessageRingConsoleConfig} 整份），
 * 服务端仍然会再钳一遍数值 —— 少一种包就少一处两边算得不一样。
 *
 * <h3>为什么右列要每次重排</h3>
 * 四段配置高度不同，隐藏一段之后下面的必须整体上移。各段各写一套固定坐标，
 * 隐藏时就会留下一串空洞（上一版挤成一团就是这么来的）。
 * 隐藏的输入框统一挪到面板外（y = -999），用 {@link #isShown} 判定。
 */
public class GuiRingConsole extends GuiContainer {

    private static final int W = 312;
    private static final int H = 240;
    private static final int M = 8;

    /** 左列（动作）与右列（设置）的分界 */
    private static final int RIGHT_X = 126;
    private static final int RIGHT_W = W - RIGHT_X - M;
    private static final int FIELD_H = 14;
    private static final int ROW_H = 18;

    private static final int ID_ACT_COLOR = 1;
    private static final int ID_ACT_ON = 2;
    private static final int ID_ACT_OFF = 3;
    private static final int ID_ACT_BLINK = 4;
    private static final int ID_MODE_COLOR = 10;
    private static final int ID_MODE_ON = 11;
    private static final int ID_MODE_OFF = 12;
    private static final int ID_MODE_BLINK = 13;
    private static final int ID_CLOSE = 31;

    /** 预设色（红 橙 黄 绿 蓝 紫 白） */
    private static final int[] PRESETS = {
            0xFF3A3A, 0xFF9A2B, 0xFFE14D, 0x4ADE6B, 0x3E9BFF, 0xA45CFF, 0xF2F6FA};

    /** 预设色块尺寸（自绘，不走 GuiButton —— 按钮底纹会把颜色盖掉） */
    private static final int SW = 20;
    private static final int SH = 12;
    private static final int SGAP = 4;

    private static final int COLOR_LABEL = 0xFF9AA7B4;
    private static final int COLOR_MUTED = 0xFF6A7480;
    private static final int COLOR_ON = 0xFF52E08A;
    private static final int COLOR_ACCENT = 0xFF6BD6FF;

    private final BlockPos pos;
    private final TileRingConsole tile;

    private final List<GuiTextField> fields = new ArrayList<GuiTextField>();
    private GuiTextField rField;
    private GuiTextField gField;
    private GuiTextField bField;
    private GuiTextField colorFadeField;
    private GuiTextField onFadeField;
    private GuiTextField offFadeField;
    private GuiTextField blinkPeriodField;
    private GuiTextField blinkTotalField;
    private GuiTextField blinkInField;
    private GuiTextField blinkOutField;

    private GuiButton actColor;
    private GuiButton actOn;
    private GuiButton actOff;
    private GuiButton actBlink;
    private GuiButton modeColor;
    private GuiButton modeOn;
    private GuiButton modeOff;
    private GuiButton modeBlink;
    private GuiButton closeBtn;

    /** 编辑中的配置（唯一的真源；输入框只是它的视图） */
    private final RingConsoleConfig edit = new RingConsoleConfig();

    /** 预设色块的命中区（自绘，所以要自己记） */
    private final int[][] presetBox = new int[PRESETS.length][4];

    /** 改过但还没发出去 */
    private boolean dirty;
    /** 距上次改动过了几 tick（边打边发太浪费，停手 4 tick 再发） */
    private int idle;
    /** "已保存"提示还剩几 tick */
    private int savedFlash;

    /** 各段此刻是否显示（决定布局、绘制，以及"哪些输入框的值要汇进 edit"） */
    private boolean showColor;
    private boolean showOn;
    private boolean showOff;
    private boolean showBlink;

    public GuiRingConsole(TileRingConsole tile, ContainerRingConsole container) {
        super(container);
        this.tile = tile;
        this.pos = tile.getPos();
        this.xSize = W;
        this.ySize = H;
        this.edit.copyFrom(tile.getConfig());
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ==================== 布局 ====================

    @Override
    public void initGui() {
        super.initGui();
        int px = this.guiLeft;
        int py = this.guiTop;

        // ---- 左列：四个动作，纵向排开（够大，不容易点错） ----
        this.actColor = add(ID_ACT_COLOR, px + M, py + 30, 110, 16, "");
        this.actOn = add(ID_ACT_ON, px + M, py + 50, 110, 16, "");
        this.actOff = add(ID_ACT_OFF, px + M, py + 70, 110, 16, "");
        this.actBlink = add(ID_ACT_BLINK, px + M, py + 90, 110, 16, "");

        // ---- 右列：控件先全造好，位置由 relayout() 按"选了什么"重排 ----
        this.modeColor = add(ID_MODE_COLOR, 0, 0, RIGHT_W - 52, ROW_H - 3, "");
        this.colorFadeField = addField(0, 0, 40);
        this.onFadeField = addField(0, 0, 40);
        this.offFadeField = addField(0, 0, 40);
        this.modeOn = add(ID_MODE_ON, 0, 0, RIGHT_W - 52, ROW_H - 3, "");
        this.modeOff = add(ID_MODE_OFF, 0, 0, RIGHT_W - 52, ROW_H - 3, "");
        this.rField = addField(0, 0, 36);
        this.gField = addField(0, 0, 36);
        this.bField = addField(0, 0, 36);
        this.blinkPeriodField = addField(0, 0, 36);
        this.blinkTotalField = addField(0, 0, 36);
        this.blinkInField = addField(0, 0, 36);
        this.blinkOutField = addField(0, 0, 36);
        this.modeBlink = add(ID_MODE_BLINK, 0, 0, RIGHT_W, ROW_H - 3, "");

        this.closeBtn = add(ID_CLOSE, px + (W - 72) / 2, py + H - 26, 72, 16,
                I18n.format("gui.jiezhimod.ring.close"));

        this.rField.setText(String.valueOf(RingColor.red(this.edit.color)));
        this.gField.setText(String.valueOf(RingColor.green(this.edit.color)));
        this.bField.setText(String.valueOf(RingColor.blue(this.edit.color)));
        this.colorFadeField.setText(seconds(this.edit.colorFadeTicks));
        this.onFadeField.setText(seconds(this.edit.onFadeTicks));
        this.offFadeField.setText(seconds(this.edit.offFadeTicks));
        this.blinkPeriodField.setText(seconds(this.edit.blinkPeriod));
        this.blinkTotalField.setText(seconds(this.edit.blinkTotal));
        this.blinkInField.setText(seconds(this.edit.blinkFadeIn));
        this.blinkOutField.setText(seconds(this.edit.blinkFadeOut));

        relayout();
        refreshButtons();
    }

    private GuiButton add(int id, int x, int y, int w, int h, String label) {
        GuiButton b = new GuiButton(id, x, y, w, h, label);
        this.buttonList.add(b);
        return b;
    }

    private GuiTextField addField(int x, int y, int w) {
        GuiTextField f = new GuiTextField(this.fields.size(), this.fontRenderer, x, y, w, FIELD_H);
        f.setMaxStringLength(7);
        this.fields.add(f);
        return f;
    }

    private static String seconds(int ticks) {
        return String.format(java.util.Locale.ROOT, "%.2f", ticks / 20.0D);
    }

    /** 按"选了什么"重排右列（隐藏的输入框挪到面板外，见类注释） */
    private void relayout() {
        this.showColor = has(RingConsoleConfig.BIT_COLOR);
        this.showOn = has(RingConsoleConfig.BIT_ON);
        this.showOff = has(RingConsoleConfig.BIT_OFF);
        this.showBlink = has(RingConsoleConfig.BIT_BLINK);

        int px = this.guiLeft;
        int x = px + RIGHT_X;
        int y = this.guiTop + 30;
        int full = RIGHT_W;
        int modeW = full - 52;

        // ---- 切换颜色 ----
        place(this.modeColor, x, y + 11, modeW, ROW_H - 3, this.showColor);
        place(this.colorFadeField, x + modeW + 8, y + 11, 40, FIELD_H, this.showColor);
        if (this.showColor) {
            y += 34;
            int fw = 36;
            int gap = 6;
            place(this.rField, x, y + 10, fw, FIELD_H, true);
            place(this.gField, x + fw + gap, y + 10, fw, FIELD_H, true);
            place(this.bField, x + (fw + gap) * 2, y + 10, fw, FIELD_H, true);
            y += 30;
            int sw = Math.min(SW, (full - SGAP * (PRESETS.length - 1)) / PRESETS.length);
            for (int i = 0; i < PRESETS.length; i++) {
                this.presetBox[i] = new int[]{x + i * (sw + SGAP), y, sw, SH};
            }
            y += SH + 8;
        } else {
            place(this.rField, 0, -999, 36, FIELD_H, false);
            place(this.gField, 0, -999, 36, FIELD_H, false);
            place(this.bField, 0, -999, 36, FIELD_H, false);
            hidePresets();
        }

        // ---- 开启灯圈 ----
        place(this.modeOn, x, y + 11, modeW, ROW_H - 3, this.showOn);
        place(this.onFadeField, x + modeW + 8, y + 11, 40, FIELD_H, this.showOn);
        if (this.showOn) {
            y += 30;
        }

        // ---- 关闭灯圈 ----
        place(this.modeOff, x, y + 11, modeW, ROW_H - 3, this.showOff);
        place(this.offFadeField, x + modeW + 8, y + 11, 40, FIELD_H, this.showOff);
        if (this.showOff) {
            y += 30;
        }

        // ---- 闪烁参数 ----
        if (this.showBlink) {
            int fw = 36;
            int gap = 44;
            place(this.blinkPeriodField, x, y + 22, fw, FIELD_H, true);
            place(this.blinkTotalField, x + fw + gap, y + 22, fw, FIELD_H, true);
            place(this.blinkInField, x, y + 44, fw, FIELD_H, true);
            place(this.blinkOutField, x + fw + gap, y + 44, fw, FIELD_H, true);
            place(this.modeBlink, x, y + 62, full, ROW_H - 3, true);
        } else {
            place(this.blinkPeriodField, 0, -999, 36, FIELD_H, false);
            place(this.blinkTotalField, 0, -999, 36, FIELD_H, false);
            place(this.blinkInField, 0, -999, 36, FIELD_H, false);
            place(this.blinkOutField, 0, -999, 36, FIELD_H, false);
            place(this.modeBlink, 0, -999, full, ROW_H - 3, false);
        }
    }

    private void hidePresets() {
        for (int i = 0; i < this.presetBox.length; i++) {
            this.presetBox[i] = new int[]{0, -999, 0, 0};
        }
    }

    private void place(GuiButton b, int x, int y, int w, int h, boolean shown) {
        if (b == null) {
            return;
        }
        b.x = x;
        b.y = y;
        b.width = w;
        b.height = h;
        b.visible = shown;
        b.enabled = shown;
    }

    private void place(GuiTextField f, int x, int y, int w, int h, boolean shown) {
        if (f == null) {
            return;
        }
        f.x = x;
        f.y = shown ? y : -999;
        f.width = w;
        f.height = h;
        if (!shown) {
            f.setFocused(false);
        }
    }

    /** 这个输入框此刻有没有显示（隐藏的框不参与取值/绘制/点击） */
    private boolean isShown(GuiTextField f) {
        return f != null && f.y > -100;
    }

    private void refreshButtons() {
        this.actColor.displayString = (has(RingConsoleConfig.BIT_COLOR) ? "■ " : "□ ")
                + I18n.format("gui.jiezhimod.ring.act.color");
        this.actOn.displayString = (has(RingConsoleConfig.BIT_ON) ? "■ " : "□ ")
                + I18n.format("gui.jiezhimod.ring.act.on");
        this.actOff.displayString = (has(RingConsoleConfig.BIT_OFF) ? "■ " : "□ ")
                + I18n.format("gui.jiezhimod.ring.act.off");
        this.actBlink.displayString = (has(RingConsoleConfig.BIT_BLINK) ? "■ " : "□ ")
                + I18n.format("gui.jiezhimod.ring.act.blink");
        this.modeColor.displayString = I18n.format(this.edit.colorFade
                ? "gui.jiezhimod.ring.mode.fade" : "gui.jiezhimod.ring.mode.instant");
        this.modeOn.displayString = I18n.format(this.edit.onFade
                ? "gui.jiezhimod.ring.mode.fadein" : "gui.jiezhimod.ring.mode.plain");
        this.modeOff.displayString = I18n.format(this.edit.offFade
                ? "gui.jiezhimod.ring.mode.fadeout" : "gui.jiezhimod.ring.mode.plain");
        this.modeBlink.displayString = I18n.format(
                this.edit.blinkFadeIn > 0 || this.edit.blinkFadeOut > 0
                        ? "gui.jiezhimod.ring.mode.blinkfade" : "gui.jiezhimod.ring.mode.blinkplain");
    }

    private boolean has(int bit) {
        return (this.edit.actions & bit) != 0;
    }

    private void toggle(int bit) {
        this.edit.actions = has(bit) ? (this.edit.actions & ~bit) : (this.edit.actions | bit);
    }

    // ==================== 交互 ====================

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_CLOSE:
                closeAndSave();
                return;

            case ID_ACT_ON:
                // ★ 开启 / 关闭互斥：后点的把另一个取消掉。
                //   两个同时选没有意义（同一刻既开又关，最终只看得到后执行的那个），
                //   实机反馈里玩家也正是被这个绕晕的。
                this.edit.actions |= RingConsoleConfig.BIT_ON;
                this.edit.actions &= ~RingConsoleConfig.BIT_OFF;
                break;
            case ID_ACT_OFF:
                this.edit.actions |= RingConsoleConfig.BIT_OFF;
                this.edit.actions &= ~RingConsoleConfig.BIT_ON;
                break;
            case ID_ACT_COLOR:
                toggle(RingConsoleConfig.BIT_COLOR);
                break;
            case ID_ACT_BLINK:
                toggle(RingConsoleConfig.BIT_BLINK);
                break;

            case ID_MODE_COLOR:
                this.edit.colorFade = !this.edit.colorFade;
                break;
            case ID_MODE_ON:
                this.edit.onFade = !this.edit.onFade;
                break;
            case ID_MODE_OFF:
                this.edit.offFade = !this.edit.offFade;
                break;
            case ID_MODE_BLINK:
                // 方式与输入框必须同时改（只改内部值的话，下一次从框里取值会把它改回去）
                if (this.edit.blinkFadeIn > 0 || this.edit.blinkFadeOut > 0) {
                    this.edit.blinkFadeIn = 0;
                    this.edit.blinkFadeOut = 0;
                } else {
                    this.edit.blinkFadeIn = 2;
                    this.edit.blinkFadeOut = 2;
                }
                this.blinkInField.setText(seconds(this.edit.blinkFadeIn));
                this.blinkOutField.setText(seconds(this.edit.blinkFadeOut));
                break;

            default:
                return;
        }
        relayout();
        refreshButtons();
        touch();
    }

    /**
     * 自绘色块的点击 + 输入框点击。
     *
     * 预设色没走 GuiButton：按钮自带底纹，会把颜色盖成一块灰（上一版就是这样，
     * 7 个格子全是空的）。这里先判命中，命中就吃掉这次点击，不再传给 super。
     */
    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && this.showColor) {
            for (int i = 0; i < PRESETS.length; i++) {
                int[] b = this.presetBox[i];
                if (b[2] > 0 && mouseX >= b[0] && mouseX < b[0] + b[2]
                        && mouseY >= b[1] && mouseY < b[1] + b[3]) {
                    this.edit.color = PRESETS[i];
                    this.edit.actions |= RingConsoleConfig.BIT_COLOR;
                    this.rField.setText(String.valueOf(RingColor.red(this.edit.color)));
                    this.gField.setText(String.valueOf(RingColor.green(this.edit.color)));
                    this.bField.setText(String.valueOf(RingColor.blue(this.edit.color)));
                    relayout();
                    refreshButtons();
                    touch();
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for (GuiTextField f : this.fields) {
            if (isShown(f)) {
                f.mouseClicked(mouseX, mouseY, mouseButton);
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        for (GuiTextField f : this.fields) {
            if (isShown(f) && f.textboxKeyTyped(typedChar, keyCode)) {
                touch();
                return;
            }
        }
        if (keyCode == 1) {          // Esc 也走"关界面并保存"
            closeAndSave();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        boolean changed = false;
        for (GuiTextField f : this.fields) {
            if (!isShown(f)) {
                continue;
            }
            boolean allowDot = f != this.rField && f != this.gField && f != this.bField;
            String before = f.getText();
            sanitize(f, allowDot);
            if (!before.equals(f.getText())) {
                changed = true;
            }
        }
        if (changed) {
            touch();
        }
        refreshButtons();

        if (this.savedFlash > 0) {
            this.savedFlash--;
        }
        // 输入停下 4 tick 再发：边打边发一个字符一个包太浪费
        if (this.dirty && ++this.idle >= 4) {
            sendNow();
        }
    }

    private static void sanitize(GuiTextField f, boolean allowDot) {
        String t = f.getText();
        StringBuilder sb = new StringBuilder();
        boolean dot = false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            } else if (allowDot && (ch == '.' || ch == ',') && !dot) {
                sb.append('.');
                dot = true;
            }
        }
        if (!sb.toString().equals(t)) {
            f.setText(sb.toString());
        }
    }

    private void touch() {
        this.dirty = true;
        this.idle = 0;
    }

    private void closeAndSave() {
        if (this.dirty) {
            sendNow();
        }
        this.mc.player.closeScreen();
        this.mc.displayGuiScreen(null);
    }

    @Override
    public void onGuiClosed() {
        // 兜底：不管怎么关（Esc / 传送 / 死亡）都要把改动落下
        if (this.dirty) {
            sendNow();
        }
        super.onGuiClosed();
    }

    /** 把**此刻显示着的**输入框汇进 edit，然后整份发给服务端 */
    private void sendNow() {
        this.edit.color = RingColor.pack(
                parseInt(this.rField, RingColor.red(this.edit.color)),
                parseInt(this.gField, RingColor.green(this.edit.color)),
                parseInt(this.bField, RingColor.blue(this.edit.color)));
        if (isShown(this.colorFadeField)) {
            this.edit.colorFadeTicks = toTicks(this.colorFadeField, this.edit.colorFadeTicks);
        }
        if (isShown(this.onFadeField)) {
            this.edit.onFadeTicks = toTicks(this.onFadeField, this.edit.onFadeTicks);
        }
        if (isShown(this.offFadeField)) {
            this.edit.offFadeTicks = toTicks(this.offFadeField, this.edit.offFadeTicks);
        }
        if (isShown(this.blinkPeriodField)) {
            this.edit.blinkPeriod = toTicks(this.blinkPeriodField, this.edit.blinkPeriod);
        }
        if (isShown(this.blinkTotalField)) {
            this.edit.blinkTotal = toTicks(this.blinkTotalField, this.edit.blinkTotal);
        }
        if (isShown(this.blinkInField)) {
            this.edit.blinkFadeIn = toTicks(this.blinkInField, this.edit.blinkFadeIn);
        }
        if (isShown(this.blinkOutField)) {
            this.edit.blinkFadeOut = toTicks(this.blinkOutField, this.edit.blinkFadeOut);
        }
        ModNetwork.CHANNEL.sendToServer(new MessageRingConsoleConfig(this.pos, this.edit));
        this.dirty = false;
        this.idle = 0;
        this.savedFlash = 30;
    }

    private static int parseInt(GuiTextField f, int fallback) {
        if (f == null) {
            return fallback;
        }
        try {
            return Math.max(0, Math.min(255, Integer.parseInt(f.getText().trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 秒 -> tick（非法就给 fallback；内部只有一个量纲：tick） */
    private static int toTicks(GuiTextField f, int fallback) {
        if (f == null) {
            return fallback;
        }
        try {
            double sec = Double.parseDouble(f.getText().trim().replace(',', '.'));
            if (Double.isNaN(sec) || Double.isInfinite(sec) || sec < 0) {
                return fallback;
            }
            return (int) Math.round(sec * 20.0D);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ==================== 绘制（全部背景层 + 绝对坐标） ====================

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int px = this.guiLeft;
        int py = this.guiTop;

        GuiDraw.drawRoundedRect(px, py, W, H, 8.0F, 0xFF0B0B10);

        String title = I18n.format("gui.jiezhimod.ring.title");
        this.fontRenderer.drawString(title,
                px + (W - this.fontRenderer.getStringWidth(title)) / 2, py + 7, 0xFFFFFFFF);
        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.actions"),
                px + M, py + 19, COLOR_LABEL);

        // 左右分栏的细线
        GuiDraw.drawRect(px + RIGHT_X - 8, py + 20, px + RIGHT_X - 7, py + H - 32, 0x33FFFFFF);

        drawLeftInfo(px, py);
        drawRight(px, py);
        drawFooter(px, py);
    }

    /** 左列下方：绑定状态 / 上次触发（按像素裁，不折行，避免压到按钮） */
    private void drawLeftInfo(int px, int py) {
        int maxW = RIGHT_X - M - 8;
        int y = py + 116;
        BlockPos ring = this.tile.getRingPos();
        String bind = ring == null
                ? I18n.format("gui.jiezhimod.ring.bind.none")
                : I18n.format("gui.jiezhimod.ring.bind.ok",
                ring.getX(), ring.getY(), ring.getZ());
        for (String line : this.fontRenderer.listFormattedStringToWidth(bind, maxW)) {
            if (y > py + 158) {
                break;
            }
            this.fontRenderer.drawString(line, px + M, y, ring == null ? 0xFFD8B25A : COLOR_ON);
            y += 10;
        }

        y += 4;
        String last = I18n.format("gui.jiezhimod.ring.last", this.tile.getLastTriggered());
        for (String line : this.fontRenderer.listFormattedStringToWidth(last, maxW)) {
            if (y > py + H - 34) {
                break;
            }
            this.fontRenderer.drawString(line, px + M, y, COLOR_MUTED);
            y += 10;
        }
    }

    /** 右列：只画选中的动作 */
    private void drawRight(int px, int py) {
        int x = px + RIGHT_X;
        int full = RIGHT_W;
        int modeW = full - 52;
        int y = py + 30;
        boolean any = false;

        if (this.showColor) {
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.row.color"),
                    x, y, COLOR_ACCENT);
            // 预览色块：贴在「切换颜色至」这一行右侧
            int sw = 24;
            int sx = x + full - sw;
            GuiDraw.drawRect(sx - 1, y - 2, sx + sw + 1, y + 9, 0x66000000);
            GuiDraw.drawRect(sx, y - 1, sx + sw, y + 8, 0xFF000000 | this.edit.color);
            y += 34;
            // RGB 三个字母画在各自框的正上方（上一版和「颜色 RGB」叠在一起）
            int fw = 36;
            int gap = 6;
            String[] tags = {"R", "G", "B"};
            int[] vals = {RingColor.red(this.edit.color), RingColor.green(this.edit.color),
                    RingColor.blue(this.edit.color)};
            for (int i = 0; i < 3; i++) {
                int bx = x + (fw + gap) * i;
                this.fontRenderer.drawString(tags[i], bx + 1, y, COLOR_MUTED);
                String v = String.valueOf(vals[i]);
                this.fontRenderer.drawString(v, bx + fw - 2 - this.fontRenderer.getStringWidth(v),
                        y, 0xFF56606A);
            }
            y += 30;
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.presets"), x, y - 10,
                    COLOR_MUTED);
            for (int i = 0; i < PRESETS.length; i++) {
                int[] b = this.presetBox[i];
                if (b[2] <= 0) {
                    continue;
                }
                boolean cur = (this.edit.color & 0xFFFFFF) == (PRESETS[i] & 0xFFFFFF);
                GuiDraw.drawRect(b[0] - 1, b[1] - 1, b[0] + b[2] + 1, b[1] + b[3] + 1,
                        cur ? 0xFFFFFFFF : 0x55000000);
                GuiDraw.drawRect(b[0], b[1], b[0] + b[2], b[1] + b[3], 0xFF000000 | PRESETS[i]);
            }
            any = true;
        }

        if (this.showOn) {
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.row.on"),
                    x, y, COLOR_ACCENT);
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.sec"),
                    x + modeW + 52, y + 14, COLOR_MUTED);
            y += 30;
            any = true;
        }
        if (this.showOff) {
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.row.off"),
                    x, y, COLOR_ACCENT);
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.sec"),
                    x + modeW + 52, y + 14, COLOR_MUTED);
            y += 30;
            any = true;
        }
        if (this.showBlink) {
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.row.blink"),
                    x, y, COLOR_ACCENT);
            int fw = 36;
            int gap = 44;
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.sec"),
                    x + modeW + 52, y + 14, COLOR_MUTED);
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.blink.period"),
                    x + fw + 4, y + 24, COLOR_MUTED);
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.blink.total"),
                    x + fw + gap + fw + 4, y + 24, COLOR_MUTED);
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.blink.in"),
                    x + fw + 4, y + 46, COLOR_MUTED);
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.blink.out"),
                    x + fw + gap + fw + 4, y + 46, COLOR_MUTED);
            any = true;
        }

        if (!any) {
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.ring.pick"),
                    x, y + 12, COLOR_MUTED);
        }
    }

    private void drawFooter(int px, int py) {
        int y = py + H - 40;
        if (this.savedFlash > 0) {
            String saved = I18n.format("gui.jiezhimod.ring.saved");
            this.fontRenderer.drawString(saved,
                    px + (W - this.fontRenderer.getStringWidth(saved)) / 2, y, COLOR_ON);
            return;
        }
        String hint = I18n.format("gui.jiezhimod.ring.hint");
        List<String> lines = this.fontRenderer.listFormattedStringToWidth(hint, W - M * 2);
        if (!lines.isEmpty()) {
            this.fontRenderer.drawString(lines.get(0), px + M, y, COLOR_MUTED);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 本项目约定：自绘一律走背景层（前景层坐标被 translate 过，混着画必偏半屏）
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (GuiTextField f : this.fields) {
            if (isShown(f)) {
                f.drawTextBox();
            }
        }
    }
}
