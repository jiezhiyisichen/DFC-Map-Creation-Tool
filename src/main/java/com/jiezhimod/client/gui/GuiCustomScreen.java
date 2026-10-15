package com.jiezhimod.client.gui;

import com.jiezhimod.block.TileCustomScreen;
import com.jiezhimod.client.util.GuiDraw;
import com.jiezhimod.dfc.DfcWatch;
import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageCustomScreenAction;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;

/**
 * 自定义显示屏的设置界面（**右键方块打开**，这是这块屏与另外两块屏最大的行为差别）。
 *
 * 界面分三段：
 *   1. 开机 / 关机按钮（需求："开机按钮在 GUI"）；
 *   2. 8 行的数据列表，每行是"设备 · 数据名 = 读数"，右侧一个"删除"按钮
 *      （需求："中有列表显示 8 个数据，可以删除数据"）；
 *   3. 底部：清空 / 关闭。
 *
 * 【列表内容从哪来】客户端方块实体里同步过来的镜像（{@code displaySlots()}）。
 * 这里**只读不写** —— 删除/开关都发 {@link MessageCustomScreenAction} 给服务端，
 * 服务端改完广播回来，界面下一帧自己就变了。客户端不维护第二份"我以为的状态"。
 */
@SideOnly(Side.CLIENT)
public class GuiCustomScreen extends GuiScreen {

    private static final int PANEL_W = 268;
    private static final int PANEL_H = 212;
    private static final int LIST_TOP = 50;
    private static final int ROW_H = 16;
    private static final int DEL_W = 40;
    private static final int MARGIN = 12;

    private static final int ID_POWER = 0;
    private static final int ID_CLEAR = 1;
    private static final int ID_CLOSE = 2;
    /** 每行"删除"按钮的 id = ID_DEL_BASE + 行号 */
    private static final int ID_DEL_BASE = 100;

    private final TileCustomScreen tile;
    private final BlockPos pos;
    private GuiButton powerButton;
    private GuiButton clearButton;
    private GuiButton[] delButtons = new GuiButton[DfcWatch.MAX];

    public GuiCustomScreen(TileCustomScreen tile) {
        this.tile = tile;
        this.pos = tile.getPos();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;

        this.powerButton = new GuiButton(ID_POWER, panelX + MARGIN, panelY + 24, 96, 18, "");
        this.buttonList.add(this.powerButton);

        for (int i = 0; i < DfcWatch.MAX; i++) {
            GuiButton b = new GuiButton(ID_DEL_BASE + i,
                    panelX + PANEL_W - MARGIN - DEL_W, panelY + LIST_TOP + i * ROW_H, DEL_W, 14,
                    I18n.format("gui.jiezhimod.custom.delete"));
            this.delButtons[i] = b;
            this.buttonList.add(b);
        }

        this.clearButton = new GuiButton(ID_CLEAR, panelX + MARGIN, panelY + PANEL_H - 26, 76, 18,
                I18n.format("gui.jiezhimod.custom.clear"));
        this.buttonList.add(this.clearButton);
        this.buttonList.add(new GuiButton(ID_CLOSE, panelX + PANEL_W / 2 - 35, panelY + PANEL_H - 26,
                70, 18, I18n.format("gui.jiezhimod.custom.close")));

        refreshButtons();
    }

    /**
     * 按钮状态跟着数据走：开机键的字样、每行"删除"是否可用。
     * 放在 updateScreen 里而不是 initGui —— 列表长度会在 GUI 开着的时候变化
     * （服务端广播回来就短了一条），只在初始化时算一次会让按钮一直是旧的。
     */
    private void refreshButtons() {
        if (this.powerButton != null) {
            this.powerButton.displayString = I18n.format(this.tile.isPowered()
                    ? "gui.jiezhimod.custom.power.off" : "gui.jiezhimod.custom.power.on");
        }
        int n = slots().size();
        for (int i = 0; i < this.delButtons.length; i++) {
            if (this.delButtons[i] != null) {
                this.delButtons[i].enabled = i < n;
            }
        }
        if (this.clearButton != null) {
            this.clearButton.enabled = n > 0;
        }
    }

    private List<DfcWatch> slots() {
        return this.tile.displaySlots();
    }

    @Override
    public void updateScreen() {
        refreshButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        int id = button.id;
        if (id == ID_CLOSE) {
            closeGui();
            return;
        }
        if (id == ID_POWER) {
            send(MessageCustomScreenAction.ACT_POWER, 0);
            return;
        }
        if (id == ID_CLEAR) {
            send(MessageCustomScreenAction.ACT_CLEAR, 0);
            return;
        }
        if (id >= ID_DEL_BASE && id < ID_DEL_BASE + DfcWatch.MAX) {
            send(MessageCustomScreenAction.ACT_DELETE, id - ID_DEL_BASE);
        }
    }

    private void send(int action, int index) {
        ModNetwork.CHANNEL.sendToServer(new MessageCustomScreenAction(this.pos, action, index));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeGui();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void closeGui() {
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

        this.drawCenteredString(this.fontRenderer, I18n.format("gui.jiezhimod.custom.title"),
                this.width / 2, panelY + 8, 0xFFFFFFFF);

        // 开机按钮右侧的状态提示（按钮本身只写"开机"/"关机"两个动作词）
        this.drawString(this.fontRenderer,
                I18n.format(this.tile.isPowered()
                        ? "gui.jiezhimod.custom.state.on" : "gui.jiezhimod.custom.state.off"),
                panelX + MARGIN + 104, panelY + 29,
                this.tile.isPowered() ? 0xFF52E08A : 0xFF888888);

        drawList(panelX, panelY);

        this.drawString(this.fontRenderer, I18n.format("gui.jiezhimod.custom.hint"),
                panelX + MARGIN, panelY + PANEL_H - 40, 0xFF777777);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawList(int panelX, int panelY) {
        List<DfcWatch> list = slots();
        int textX = panelX + MARGIN;
        int delX = panelX + PANEL_W - MARGIN - DEL_W;

        for (int i = 0; i < DfcWatch.MAX; i++) {
            int rowY = panelY + LIST_TOP + i * ROW_H;
            // 隔行深浅底：8 行挤在一起，没有斑马纹很容易看串行
            if ((i & 1) == 0) {
                GuiDraw.drawRoundedRect(panelX + MARGIN - 2, rowY - 1, PANEL_W - MARGIN * 2 + 4,
                        ROW_H, 2.0F, 0x22FFFFFF);
            }
            String num = (i + 1) + ".";
            this.drawString(this.fontRenderer, num, textX, rowY + 4, 0xFF666666);

            if (i >= list.size()) {
                this.drawString(this.fontRenderer, I18n.format("gui.jiezhimod.custom.empty"),
                        textX + 14, rowY + 4, 0xFF444444);
                continue;
            }

            DfcWatch w = list.get(i);
            String left = w.device + " · " + (w.name == null || w.name.isEmpty() ? "--" : w.name);
            if (left.length() > 22) {
                left = left.substring(0, 22) + "…";
            }
            this.drawString(this.fontRenderer, left, textX + 14, rowY + 4,
                    w.alive ? 0xFFCCCCCC : 0xFF995555);

            String value = w.alive ? w.value : I18n.format("gui.jiezhimod.custom.offline");
            // 数值右对齐贴着"删除"按钮左边，长读数（"1.23 GHE / 2.50 GHE"）也放得下
            int vx = delX - 6 - this.fontRenderer.getStringWidth(value);
            this.drawString(this.fontRenderer, value, Math.max(textX + 120, vx), rowY + 4,
                    w.alive ? 0xFF48D6FF : 0xFF995555);
        }
    }
}
