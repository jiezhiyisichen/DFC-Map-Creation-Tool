package com.jiezhimod.client.keypad;

import com.jiezhimod.block.ContainerPasswordConsole;
import com.jiezhimod.block.TileKeypad;
import com.jiezhimod.block.TilePasswordConsole;
import com.jiezhimod.client.util.GuiDraw;
import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageConsoleConfig;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;

/**
 * 密码控制台的配置界面。
 *
 * 能配两件事：
 *   1. **收到红石信号后干什么** —— 五个互斥选项；
 *   2. **密码正确时是否输出红石信号** —— 开关。
 *
 * 【为什么是 GuiContainer 而不是 GuiScreen】服务端必须先建一个容器才能开界面，
 * 这一页虽然没有物品槽，也照 {@link ContainerPasswordConsole} 那套走
 * （与红石延时器一致）。
 *
 * 【坐标系】沿用本项目约定：`drawGuiContainerBackgroundLayer` 里拿到的是**屏幕绝对坐标**，
 * 所以全部用 `px + 偏移`；前景层留空（见 {@code GuiCustomScreen} 类注释）。
 *
 * 【状态从哪来】界面上显示的选中项全部读方块实体的**客户端镜像**
 * （服务端广播回来的）。点按钮只发一个 {@link MessageConsoleConfig}，
 * 不本地改状态 —— 服务端拒绝时界面不会显示假选中。
 */
public class GuiPasswordConsole extends GuiContainer {

    private static final int W = 248;
    private static final int H = 156;
    private static final int BTN_W = 106;
    private static final int BTN_H = 16;
    private static final int COL1 = 14;
    private static final int COL2 = 128;
    private static final int ROW1 = 62;
    private static final int ROW_STEP = 18;

    private static final int ID_ACTION_BASE = 0;
    private static final int ID_OUTPUT = 10;
    private static final int ID_CLOSE = 11;

    private final TilePasswordConsole tile;
    private final BlockPos pos;

    private final GuiButton[] actionButtons = new GuiButton[TilePasswordConsole.ACTION_COUNT];
    private GuiButton outputButton;

    public GuiPasswordConsole(TilePasswordConsole tile, ContainerPasswordConsole container) {
        super(container);
        this.tile = tile;
        this.pos = tile.getPos();
        // initGui() 用这两个值算 guiLeft/guiTop，必须在构造器里就位
        this.xSize = W;
        this.ySize = H;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        super.initGui();
        int px = this.guiLeft;
        int py = this.guiTop;

        for (int i = 0; i < TilePasswordConsole.ACTION_COUNT; i++) {
            int col = i % 2;
            int row = i / 2;
            GuiButton b = new GuiButton(ID_ACTION_BASE + i,
                    px + (col == 0 ? COL1 : COL2), py + ROW1 + row * ROW_STEP,
                    BTN_W, BTN_H, "");
            this.actionButtons[i] = b;
            this.buttonList.add(b);
        }

        this.outputButton = new GuiButton(ID_OUTPUT, px + W - 14 - 64, py + 114, 64, BTN_H, "");
        this.buttonList.add(this.outputButton);

        this.buttonList.add(new GuiButton(ID_CLOSE, px + W / 2 - 32, py + 132, 64, BTN_H,
                I18n.format("gui.jiezhimod.console.close")));

        refreshButtons();
    }

    /** 选中项前面加 ▶，未选中加 · —— 状态跟着服务端镜像走 */
    private void refreshButtons() {
        int cur = this.tile.getRedstoneAction();
        for (int i = 0; i < this.actionButtons.length; i++) {
            if (this.actionButtons[i] == null) {
                continue;
            }
            this.actionButtons[i].displayString =
                    (i == cur ? "▶ " : "· ") + I18n.format(actionKey(i));
        }
        if (this.outputButton != null) {
            this.outputButton.displayString = I18n.format(this.tile.isOutputWhenCorrect()
                    ? "gui.jiezhimod.console.on" : "gui.jiezhimod.console.off");
        }
    }

    private static String actionKey(int action) {
        switch (action) {
            case TilePasswordConsole.ACT_LOCK:
                return "gui.jiezhimod.console.act.lock";
            case TilePasswordConsole.ACT_UNLOCK:
                return "gui.jiezhimod.console.act.release";
            case TilePasswordConsole.ACT_CLEAR:
                return "gui.jiezhimod.console.act.clear";
            case TilePasswordConsole.ACT_LOCK_CLEAR:
                return "gui.jiezhimod.console.act.lock_clear";
            default:
                return "gui.jiezhimod.console.act.none";
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        refreshButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == ID_CLOSE) {
            this.mc.displayGuiScreen(null);
            return;
        }
        if (button.id == ID_OUTPUT) {
            send(this.tile.getRedstoneAction(), !this.tile.isOutputWhenCorrect());
            return;
        }
        if (button.id >= ID_ACTION_BASE
                && button.id < ID_ACTION_BASE + TilePasswordConsole.ACTION_COUNT) {
            send(button.id - ID_ACTION_BASE, this.tile.isOutputWhenCorrect());
        }
    }

    private void send(int action, boolean output) {
        ModNetwork.CHANNEL.sendToServer(new MessageConsoleConfig(this.pos, action, output));
    }

    // ==================== 绘制（背景层 / 绝对坐标） ====================

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        // 整段兜底：界面画不出来时，至少留一条日志和一行能看见的提示，
        // 而不是"鼠标出现了、屏幕一片空白"。
        try {
            drawPanelSafe();
        } catch (Throwable t) {
            if (!LOGGED_PAINT_ERROR) {
                LOGGED_PAINT_ERROR = true;
                net.minecraft.client.Minecraft.getMinecraft().fontRenderer.drawString(
                        "GUI paint error: " + t, 8, 8, 0xFFFF4040);
                com.jiezhimod.JiezhiMod.warn("[GuiPasswordConsole] paint failed: "
                        + t.getClass().getName() + ": " + t.getMessage());
            }
        }
    }

    /** 排错用：绘制异常只打一次 */
    private static boolean LOGGED_PAINT_ERROR;

    private void drawPanelSafe() {
        int px = this.guiLeft;
        int py = this.guiTop;

        // 不透明底：背后就是世界，半透明会和方块本身糊在一起
        GuiDraw.drawRoundedRect(px, py, W, H, 0.0F, 0xFF0B0B10);

        String title = I18n.format("gui.jiezhimod.console.title");
        this.fontRenderer.drawString(title,
                px + (W - this.fontRenderer.getStringWidth(title)) / 2, py + 6, 0xFFFFFFFF);

        BlockPos keypad = this.tile.getKeypadPos();
        String linkText;
        int linkColor;
        if (keypad == null) {
            linkText = I18n.format("gui.jiezhimod.console.unlinked");
            linkColor = 0xFFE0736B;
        } else {
            linkText = I18n.format("gui.jiezhimod.console.linked",
                    keypad.getX(), keypad.getY(), keypad.getZ());
            linkColor = 0xFF7FE3A0;
        }
        this.fontRenderer.drawString(clip(linkText, W - 28), px + 14, py + 22, linkColor);
        this.fontRenderer.drawString(clip(keypadStateText(), W - 28), px + 14, py + 34, 0xFF9AA7B4);

        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.console.onsignal"),
                px + 14, py + 50, 0xFF8FA0B0);
        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.console.output"),
                px + 14, py + 118, 0xFF8FA0B0);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 故意留空：全部画在背景层（绝对坐标）
    }

    private String keypadStateText() {
        TileKeypad kp = this.tile.getKeypad();
        if (kp == null) {
            return I18n.format("gui.jiezhimod.console.keypad.none");
        }
        if (kp.isLocked()) {
            return I18n.format("gui.jiezhimod.console.keypad.locked");
        }
        if (kp.isAccepted()) {
            return I18n.format("gui.jiezhimod.console.keypad.accepted");
        }
        return I18n.format("gui.jiezhimod.console.keypad.normal");
    }

    /** 按像素裁剪，长坐标串不会顶出面板 */
    private String clip(String s, int maxWidth) {
        return this.fontRenderer.trimStringToWidth(s, maxWidth);
    }
}
