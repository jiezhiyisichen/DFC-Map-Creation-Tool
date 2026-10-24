package com.jiezhimod.client.gui;

import com.jiezhimod.block.BlockAdvancedRepeater;
import com.jiezhimod.block.ContainerAdvancedRepeater;
import com.jiezhimod.block.TileAdvancedRepeater;
import com.jiezhimod.client.util.GuiDraw;
import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageRepeaterDelay;
import com.jiezhimod.repeater.RepeaterDelay;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.Locale;

/**
 * 高级红石中继器的延迟设置界面（右键方块打开）。
 *
 * <h3>两种单位</h3>
 * 顶部两个按钮切「tick」和「秒」：
 * <ul>
 *   <li><b>tick</b> —— 游戏刻，1 秒 = 20 tick，范围 1 ~ 1200；</li>
 *   <li><b>秒</b> —— 保留 2 位小数（1 tick = 0.05 秒），范围 0.05 ~ 60.00。</li>
 * </ul>
 * 切换单位时**输入框里的数字会跟着换算**，而不是留在原单位里让玩家自己算。
 * 无论用哪个单位输入，发给服务端的一律是 tick —— 内部只有一个量纲。
 *
 * <h3>为什么不是滑块</h3>
 * 范围跨了三个数量级（1 tick 到 60 秒），线性滑块会把 0~1 秒全挤在最左边几像素里。
 * 数值框 + 四档步进按钮（tick 模式 ±1/±10，秒模式 ±0.10/±1.00）能精确到每一刻。
 *
 * <h3>数值从哪来</h3>
 * 读客户端方块实体的镜像（服务端通过 {@code SPacketUpdateTileEntity} 同步），
 * 界面上不做臆测；按「应用」才发包，服务端钳完范围再广播回来。
 * 步进按钮**不直接生效**，只改输入框里的数字 —— 一次调整只发一个包，且能反悔。
 */
public class GuiAdvancedRepeater extends GuiContainer {

    private static final int W = 248;
    private static final int H = 180;

    private static final int ID_UNIT_TICK = 1;
    private static final int ID_UNIT_SECOND = 2;
    private static final int ID_MINUS_BIG = 3;
    private static final int ID_MINUS = 4;
    private static final int ID_PLUS = 5;
    private static final int ID_PLUS_BIG = 6;
    private static final int ID_APPLY = 7;
    private static final int ID_CLOSE = 8;

    /** 秒模式下的两档步进（tick 模式下是 1 / 10 tick） */
    private static final double STEP_SECOND_SMALL = 0.10D;
    private static final double STEP_SECOND_BIG = 1.00D;

    private static final int COLOR_LABEL = 0xFF8FA0B0;
    private static final int COLOR_OK = 0xFF7FE3A0;
    private static final int COLOR_WARN = 0xFFD8B25A;
    private static final int COLOR_BAD = 0xFFFF8080;
    private static final int COLOR_MUTED = 0xFF6E7A86;

    private final BlockPos pos;
    private final TileAdvancedRepeater tile;   // 可能为 null（方块已被拆）

    private GuiTextField valueField;
    /** 当前编辑单位：true = 秒，false = tick */
    private boolean unitSeconds;

    /** 底部提示（应用成功 / 输入非法） */
    private String hint = "";
    private int hintColor = COLOR_LABEL;

    public GuiAdvancedRepeater(BlockPos pos, ContainerAdvancedRepeater container) {
        super(container);
        this.pos = pos;
        this.xSize = W;
        this.ySize = H;

        TileEntity te = null;
        if (Minecraft.getMinecraft().world != null) {
            te = Minecraft.getMinecraft().world.getTileEntity(pos);
        }
        this.tile = te instanceof TileAdvancedRepeater ? (TileAdvancedRepeater) te : null;
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

        this.valueField = new GuiTextField(0, this.fontRenderer, px + 92, py + 79, 100, 18);
        this.valueField.setMaxStringLength(12);
        this.valueField.setText(formatValue(currentTicks()));

        this.buttonList.add(new GuiButton(ID_UNIT_TICK, px + 14, py + 56, 104, 18, ""));
        this.buttonList.add(new GuiButton(ID_UNIT_SECOND, px + 120, py + 56, 104, 18, ""));

        this.buttonList.add(new GuiButton(ID_MINUS_BIG, px + 14, py + 108, 50, 18, ""));
        this.buttonList.add(new GuiButton(ID_MINUS, px + 70, py + 108, 50, 18, ""));
        this.buttonList.add(new GuiButton(ID_PLUS, px + 126, py + 108, 50, 18, ""));
        this.buttonList.add(new GuiButton(ID_PLUS_BIG, px + 182, py + 108, 50, 18, ""));

        this.buttonList.add(new GuiButton(ID_APPLY, px + 14, py + 156, 110, 18,
                I18n.format("gui.jiezhimod.repeater.apply")));
        this.buttonList.add(new GuiButton(ID_CLOSE, px + 130, py + 156, 104, 18,
                I18n.format("gui.jiezhimod.repeater.close")));

        refreshButtons();
        setHint("", COLOR_LABEL);
    }

    /** 按当前单位刷新按钮文案（▶ 标出正在用的单位，步进按钮标出步长） */
    private void refreshButtons() {
        setButtonText(ID_UNIT_TICK, (this.unitSeconds ? "· " : "▶ ")
                + I18n.format("gui.jiezhimod.repeater.unit.tick"));
        setButtonText(ID_UNIT_SECOND, (this.unitSeconds ? "▶ " : "· ")
                + I18n.format("gui.jiezhimod.repeater.unit.second"));

        String small = this.unitSeconds
                ? String.format(Locale.ROOT, "%.2f", STEP_SECOND_SMALL) : "1";
        String big = this.unitSeconds
                ? String.format(Locale.ROOT, "%.2f", STEP_SECOND_BIG) : "10";
        setButtonText(ID_MINUS_BIG, "-" + big);
        setButtonText(ID_MINUS, "-" + small);
        setButtonText(ID_PLUS, "+" + small);
        setButtonText(ID_PLUS_BIG, "+" + big);
    }

    private void setButtonText(int id, String text) {
        GuiButton b = findButton(id);
        if (b != null) {
            b.displayString = text;
        }
    }

    private GuiButton findButton(int id) {
        for (Object o : this.buttonList) {
            GuiButton b = (GuiButton) o;
            if (b.id == id) {
                return b;
            }
        }
        return null;
    }

    // ==================== 数据 ====================

    private int currentTicks() {
        return this.tile == null ? RepeaterDelay.DEFAULT_TICKS : this.tile.getDelay();
    }

    private boolean locked() {
        if (Minecraft.getMinecraft().world == null) {
            return false;
        }
        IBlockState state = Minecraft.getMinecraft().world.getBlockState(this.pos);
        return state.getBlock() instanceof BlockAdvancedRepeater
                && state.getValue(BlockAdvancedRepeater.LOCKED);
    }

    /** 数值框里该写什么（按当前单位） */
    private String formatValue(int ticks) {
        if (this.unitSeconds) {
            return RepeaterDelay.secondsText(ticks);
        }
        return String.valueOf(RepeaterDelay.clamp(ticks));
    }

    /** 把界面上的输入解析成 tick；非法返回 -1 */
    private int parseInput() {
        double v = RepeaterDelay.parse(this.valueField == null ? "" : this.valueField.getText());
        if (Double.isNaN(v)) {
            return -1;
        }
        double low = this.unitSeconds
                ? RepeaterDelay.toSeconds(RepeaterDelay.MIN_TICKS)
                : RepeaterDelay.MIN_TICKS;
        double high = this.unitSeconds
                ? RepeaterDelay.toSeconds(RepeaterDelay.MAX_TICKS)
                : RepeaterDelay.MAX_TICKS;
        // 允许半个刻度的误差（秒模式下玩家可能填 0.07）
        if (v < low - 0.5D / RepeaterDelay.TICKS_PER_SECOND || v > high + 0.5D / RepeaterDelay.TICKS_PER_SECOND) {
            return -1;
        }
        return this.unitSeconds ? RepeaterDelay.fromSeconds(v) : (int) Math.round(v);
    }

    private void setHint(String text, int color) {
        this.hint = text;
        this.hintColor = color;
    }

    // ==================== 交互 ====================

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_CLOSE:
                this.mc.displayGuiScreen(null);
                return;

            case ID_APPLY: {
                int ticks = parseInput();
                if (ticks < 0) {
                    setHint(I18n.format("gui.jiezhimod.repeater.bad",
                            RepeaterDelay.MIN_TICKS, RepeaterDelay.MAX_TICKS,
                            I18n.format(this.unitSeconds
                                    ? "gui.jiezhimod.repeater.unit.second"
                                    : "gui.jiezhimod.repeater.unit.tick")), COLOR_BAD);
                    return;
                }
                // 客户端先乐观更新：本地镜像跟着改，界面上的"当前延迟"立刻就是生效值
                if (this.tile != null) {
                    this.tile.setDelay(ticks);
                }
                ModNetwork.CHANNEL.sendToServer(new MessageRepeaterDelay(this.pos, ticks));
                this.valueField.setText(formatValue(ticks));
                setHint(I18n.format("gui.jiezhimod.repeater.ok", ticks,
                        RepeaterDelay.secondsText(ticks)), COLOR_OK);
                return;
            }

            case ID_UNIT_TICK:
            case ID_UNIT_SECOND: {
                boolean wantSeconds = button.id == ID_UNIT_SECOND;
                if (wantSeconds == this.unitSeconds) {
                    return;
                }
                int ticks = currentTicks();
                this.unitSeconds = wantSeconds;
                this.valueField.setText(formatValue(ticks));
                setHint("", COLOR_LABEL);
                refreshButtons();
                return;
            }

            default:
                break;
        }

        // 步进按钮：在当前单位上加减，写回输入框，等玩家按「应用」
        int ticks = currentTicks();
        switch (button.id) {
            case ID_MINUS_BIG:
                ticks -= this.unitSeconds ? RepeaterDelay.fromSeconds(STEP_SECOND_BIG) : 10;
                break;
            case ID_MINUS:
                ticks -= this.unitSeconds ? RepeaterDelay.fromSeconds(STEP_SECOND_SMALL) : 1;
                break;
            case ID_PLUS:
                ticks += this.unitSeconds ? RepeaterDelay.fromSeconds(STEP_SECOND_SMALL) : 1;
                break;
            case ID_PLUS_BIG:
                ticks += this.unitSeconds ? RepeaterDelay.fromSeconds(STEP_SECOND_BIG) : 10;
                break;
            default:
                return;
        }
        this.valueField.setText(formatValue(RepeaterDelay.clamp(ticks)));
        setHint("", COLOR_LABEL);
    }

    // ==================== 绘制 ====================

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int px = this.guiLeft;
        int py = this.guiTop;

        GuiDraw.drawRoundedRect(px, py, W, H, 8.0F, 0xFF0B0B10);

        String title = I18n.format("gui.jiezhimod.repeater.title");
        this.fontRenderer.drawString(title,
                px + (W - this.fontRenderer.getStringWidth(title)) / 2, py + 6, 0xFFFFFFFF);

        // 当前生效值：tick 与秒一起写出来，玩家不用自己换算
        int cur = currentTicks();
        this.fontRenderer.drawString(
                I18n.format("gui.jiezhimod.repeater.current", cur, RepeaterDelay.secondsText(cur)),
                px + 14, py + 26, COLOR_OK);

        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.repeater.unit"), px + 14, py + 44, COLOR_LABEL);
        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.repeater.value"), px + 14, py + 84, COLOR_LABEL);

        // 第 128 行：平时写范围；被侧面中继器锁住时改写成锁定提示（两者不同时出现，避免挤行）
        if (locked()) {
            this.fontRenderer.drawString(
                    this.fit(I18n.format("gui.jiezhimod.repeater.locked"), W - 28),
                    px + 14, py + 128, COLOR_WARN);
        } else {
            this.fontRenderer.drawString(
                    this.fit(I18n.format("gui.jiezhimod.repeater.hint.range",
                            RepeaterDelay.MIN_TICKS, RepeaterDelay.MAX_TICKS), W - 28),
                    px + 14, py + 128, COLOR_MUTED);
        }

        if (this.hint != null && !this.hint.isEmpty()) {
            this.fontRenderer.drawString(this.fit(this.hint, W - 28), px + 14, py + 140, this.hintColor);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 全部画在背景层（本项目约定：自绘一律走背景层 + 绝对坐标）
    }

    /** 输入框必须在这里画：它由 GuiScreen 的绘制流程管理，不属于"背景层自绘" */
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (this.valueField != null) {
            this.valueField.drawTextBox();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.valueField != null && this.valueField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        // 回车 = 应用（比"再去点按钮"顺手）
        if (keyCode == 28 && this.valueField != null && this.valueField.isFocused()) {
            GuiButton apply = findButton(ID_APPLY);
            if (apply != null) {
                actionPerformed(apply);
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.valueField != null) {
            this.valueField.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // 输入框只收数字；小数点只在「秒」模式下允许（tick 只能整数）
        if (this.valueField != null) {
            String t = this.valueField.getText();
            StringBuilder sb = new StringBuilder();
            boolean dotUsed = false;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c >= '0' && c <= '9') {
                    sb.append(c);
                } else if ((c == '.' || c == ',') && this.unitSeconds && !dotUsed) {
                    sb.append('.');
                    dotUsed = true;
                }
            }
            if (!sb.toString().equals(t)) {
                this.valueField.setText(sb.toString());
            }
        }
        refreshButtons();
    }

    private String fit(String s, int maxWidth) {
        return this.fontRenderer.trimStringToWidth(s, maxWidth);
    }
}
