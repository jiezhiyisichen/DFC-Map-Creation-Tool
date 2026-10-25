package com.jiezhimod.client.gui;

import com.jiezhimod.block.ContainerLaserScreen;
import com.jiezhimod.block.TileLaserScreen;
import com.jiezhimod.client.util.GuiDraw;
import com.jiezhimod.dfc.DfcSnapshot;
import com.jiezhimod.dfc.LaserInfo;
import com.jiezhimod.dfc.LaserLayout;
import com.jiezhimod.item.ItemLaserConnector;
import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageCustomScreenAction;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 激光状态显示器的设置界面（**右键方块打开**）。
 *
 * <h3>为什么单独写一个界面</h3>
 * v4.2 直接套用了自定义屏的面板，于是：
 *   · 列表是 **8 行**（自定义屏的数据槽数），这块屏明明只有
 *     {@link LaserLayout#SLOTS} 台激光 —— 下面四行永远是"（空）"；
 *   · 行里画的是"设备 · 罐名 … 读数"，那是自定义屏的语义，看不出
 *     "这台是稳定器还是发射器"；
 *   · 槽位只收 DFC 遥控器，等于逼玩家用遥控器去操作一块和它无关的屏。
 * 现在这块屏有自己的界面：**固定 4 行，一行一台激光，行首就是类型短名**
 * （SL1 / PL1 / OL1 / INJ1，颜色按类型区分），槽位只收激光链接器。
 *
 * <h3>版面</h3>
 * 左区：开机 / 关机、**激光链接器槽位**、「添加」、「清空」/「关闭」；
 * 右区：4 行激光，每行两行字（类型 + 类型名 / 第一条读数），右侧一个「删除」；
 * 底部：一句提示 + 玩家背包。
 *
 * <h3>数据从哪来</h3>
 * 客户端方块实体里同步过来的 {@link DfcSnapshot#lasers}（服务端每 200ms 重读一遍）。
 * 界面**只读不写**：删除 / 清空 / 添加 / 开关机 都发
 * {@link MessageCustomScreenAction} 给服务端，服务端改完广播回来，下一帧自己就变了。
 */
@SideOnly(Side.CLIENT)
public class GuiLaserScreen extends GuiContainer {

    private static final int W = ContainerLaserScreen.PANEL_W;
    private static final int H = ContainerLaserScreen.PANEL_H;
    private static final int MARGIN = ContainerLaserScreen.MARGIN;

    private static final int SLOT_BOX = 18;

    private static final int ID_POWER = 0;
    private static final int ID_CLEAR = 1;
    private static final int ID_CLOSE = 2;
    private static final int ID_ADD = 3;
    /** 每行"删除"按钮的 id = ID_DEL_BASE + 行号 */
    private static final int ID_DEL_BASE = 100;

    private static final int COLOR_LABEL = 0xFF9AA7B4;
    private static final int COLOR_MUTED = 0xFF6A7480;
    private static final int COLOR_FAINT = 0xFF444A52;
    private static final int COLOR_VALUE = 0xFF48D6FF;
    private static final int COLOR_OFFLINE = 0xFF995555;

    private final TileLaserScreen tile;
    private final BlockPos pos;

    private GuiButton powerButton;
    private GuiButton clearButton;
    private GuiButton addButton;
    private final GuiButton[] delButtons = new GuiButton[LaserLayout.SLOTS];

    public GuiLaserScreen(TileLaserScreen tile, ContainerLaserScreen container) {
        super(container);
        this.tile = tile;
        this.pos = tile.getPos();
        // GuiContainer.initGui 用这两个值算 guiLeft/guiTop，必须在 super.initGui() 之前就位
        this.xSize = W;
        this.ySize = H;
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

        this.powerButton = new GuiButton(ID_POWER, px + ContainerLaserScreen.POWER_X,
                py + ContainerLaserScreen.POWER_Y, ContainerLaserScreen.POWER_W, 16, "");
        this.buttonList.add(this.powerButton);

        this.addButton = new GuiButton(ID_ADD, px + ContainerLaserScreen.ADD_BTN_X,
                py + ContainerLaserScreen.ADD_BTN_Y, ContainerLaserScreen.ADD_BTN_W, 16,
                I18n.format("gui.jiezhimod.laser.add"));
        this.buttonList.add(this.addButton);

        int delX = px + W - MARGIN - ContainerLaserScreen.DEL_W;
        for (int i = 0; i < LaserLayout.SLOTS; i++) {
            GuiButton b = new GuiButton(ID_DEL_BASE + i, delX,
                    py + ContainerLaserScreen.LIST_TOP + i * ContainerLaserScreen.ROW_H
                            + ContainerLaserScreen.DEL_DY,
                    ContainerLaserScreen.DEL_W, ContainerLaserScreen.DEL_H,
                    I18n.format("gui.jiezhimod.laser.delete"));
            this.delButtons[i] = b;
            this.buttonList.add(b);
        }

        this.clearButton = new GuiButton(ID_CLEAR, px + ContainerLaserScreen.CLEAR_X,
                py + ContainerLaserScreen.CLEAR_Y, ContainerLaserScreen.CLEAR_W, 16,
                I18n.format("gui.jiezhimod.laser.clear"));
        this.buttonList.add(this.clearButton);
        this.buttonList.add(new GuiButton(ID_CLOSE, px + ContainerLaserScreen.CLOSE_X,
                py + ContainerLaserScreen.CLEAR_Y, ContainerLaserScreen.CLOSE_W, 16,
                I18n.format("gui.jiezhimod.laser.close")));

        refreshButtons();
    }

    /**
     * 按钮状态跟着数据走。放在 updateScreen 里而不是 initGui ——
     * 面板开着的时候列表长度就会变（服务端广播回来就少了一台）。
     */
    private void refreshButtons() {
        if (this.powerButton != null) {
            this.powerButton.displayString = I18n.format(this.tile.isPowered()
                    ? "gui.jiezhimod.laser.power.off" : "gui.jiezhimod.laser.power.on");
        }
        int n = lasers().size();
        for (int i = 0; i < this.delButtons.length; i++) {
            if (this.delButtons[i] != null) {
                this.delButtons[i].enabled = i < n;
            }
        }
        if (this.clearButton != null) {
            this.clearButton.enabled = n > 0;
        }
        if (this.addButton != null) {
            // 没放链接器时按钮是灰的 —— 否则玩家点一下没反应，只会以为界面坏了
            this.addButton.enabled = connectorInSlot() != null;
        }
    }

    /** 同步过来的四台激光（没同步到就是空表，界面照样画 4 行"空"） */
    private List<LaserInfo> lasers() {
        DfcSnapshot d = this.tile.getData();
        if (d == null || d.lasers == null) {
            return Collections.emptyList();
        }
        return d.lasers;
    }

    /** 槽位里的激光链接器（不是链接器或空着都返回 null） */
    private ItemStack connectorInSlot() {
        if (this.inventorySlots == null || this.inventorySlots.getSlot(0) == null) {
            return null;
        }
        ItemStack s = this.inventorySlots.getSlot(0).getStack();
        if (s == null || s.isEmpty() || !(s.getItem() instanceof ItemLaserConnector)) {
            return null;
        }
        return s;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        refreshButtons();
    }

    // ==================== 交互 ====================

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
        if (id == ID_ADD) {
            send(MessageCustomScreenAction.ACT_IMPORT, 0);
            return;
        }
        if (id >= ID_DEL_BASE && id < ID_DEL_BASE + LaserLayout.SLOTS) {
            send(MessageCustomScreenAction.ACT_DELETE, id - ID_DEL_BASE);
        }
    }

    private void send(int action, int index) {
        ModNetwork.CHANNEL.sendToServer(new MessageCustomScreenAction(this.pos, action, index));
    }

    /**
     * 关界面。
     *
     * 【必须是 closeScreen()】{@code EntityPlayerSP.closeScreen()} 才会给服务端发关闭包，
     * 服务端收到才会走 {@code onContainerClosed} 把链接器还给玩家。
     */
    private void closeGui() {
        if (this.mc.player != null) {
            this.mc.player.closeScreen();
        }
        this.mc.displayGuiScreen(null);
    }

    // ==================== 绘制：全部在背景层，用绝对坐标 ====================

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int px = this.guiLeft;
        int py = this.guiTop;

        // 完全不透明：这块屏在世界里本身就画着引导文案，留一点透明就会透出来叠在一起
        GuiDraw.drawRoundedRect(px, py, W, H, 8.0F, 0xFF0B0B10);

        drawTitle(px, py);
        drawPowerState(px, py);
        drawConnectorRow(px, py);
        drawAddHint(px, py);
        drawCount(px, py);
        drawList(px, py);
        drawHint(px, py);
        drawPlayerInventory(px, py);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 故意留空：全部画在背景层（绝对坐标），见自定义屏 GUI 的同一段说明
    }

    private void drawTitle(int px, int py) {
        String title = I18n.format("gui.jiezhimod.laser.title");
        this.fontRenderer.drawString(title, px + (W - this.fontRenderer.getStringWidth(title)) / 2,
                py + ContainerLaserScreen.TITLE_Y, 0xFFFFFFFF);
    }

    private void drawPowerState(int px, int py) {
        this.fontRenderer.drawString(
                I18n.format(this.tile.isPowered()
                        ? "gui.jiezhimod.laser.state.on" : "gui.jiezhimod.laser.state.off"),
                px + MARGIN, py + ContainerLaserScreen.STATE_Y,
                this.tile.isPowered() ? 0xFF52E08A : 0xFF888888);
    }

    /** 链接器槽位那一行：槽底 + 标签 + 已记录台数 */
    private void drawConnectorRow(int px, int py) {
        int sx = px + ContainerLaserScreen.LINK_SLOT_X;
        int sy = py + ContainerLaserScreen.LINK_SLOT_Y;
        drawSlotBox(sx, sy);

        String label = I18n.format("gui.jiezhimod.laser.connector");
        int lx = px + ContainerLaserScreen.LINK_LABEL_X;
        this.fontRenderer.drawString(label, lx, py + ContainerLaserScreen.LINK_LABEL_Y, COLOR_LABEL);

        ItemStack conn = connectorInSlot();
        String text;
        int color;
        if (conn == null) {
            text = I18n.format("gui.jiezhimod.laser.connector.empty");
            color = COLOR_MUTED;
        } else {
            int n = ItemLaserConnector.deviceCount(conn);
            text = I18n.format("gui.jiezhimod.laser.connector.count", n,
                    ItemLaserConnector.MAX_DEVICES);
            color = n > 0 ? 0xFF7FE3A0 : 0xFFD8B25A;
        }
        // 只在左区内排版，超出按像素裁（不压到右区列表）
        int maxW = Math.max(12, (px + ContainerLaserScreen.LIST_X - 4) - lx);
        this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(text, maxW),
                lx, py + ContainerLaserScreen.LINK_COUNT_Y, color);
    }

    private void drawAddHint(int px, int py) {
        int hx = px + ContainerLaserScreen.ADD_BTN_X;
        int maxW = Math.max(12, (px + ContainerLaserScreen.LIST_X - 4) - hx);
        this.fontRenderer.drawString(
                this.fontRenderer.trimStringToWidth(
                        I18n.format("gui.jiezhimod.laser.add.hint"), maxW),
                hx, py + ContainerLaserScreen.ADD_HINT_Y, COLOR_MUTED);
    }

    private void drawCount(int px, int py) {
        this.fontRenderer.drawString(
                I18n.format("gui.jiezhimod.laser.count", lasers().size(), LaserLayout.SLOTS),
                px + MARGIN, py + ContainerLaserScreen.COUNT_Y, COLOR_LABEL);
    }

    /**
     * 右区：**固定 4 行**，一行一台激光。
     *
     * 每行两行字：
     *   · 第一行 = 类型短名 + 类型中文名（颜色按类型：SL 黄 / PL 红 / OL 浅蓝 / 喷射器白）；
     *   · 第二行 = 第一条读数（列表空间有限，详细的五行在屏幕上看），
     *     带一条细数据条；离线时写"离线"，没接入的行写"（空）"。
     */
    private void drawList(int px, int py) {
        List<LaserInfo> list = lasers();
        int textX = px + ContainerLaserScreen.LIST_X;
        int rightX = px + W - MARGIN - ContainerLaserScreen.DEL_W - 4;
        int rowW = (px + W - MARGIN) - textX + 4;

        for (int i = 0; i < LaserLayout.SLOTS; i++) {
            int rowY = py + ContainerLaserScreen.LIST_TOP + i * ContainerLaserScreen.ROW_H;
            // 隔行深浅底：4 行挤在一起，没有斑马纹很容易看串行
            if ((i & 1) == 0) {
                GuiDraw.drawRoundedRect(textX - 4, rowY - 1, rowW,
                        ContainerLaserScreen.ROW_H, 2.0F, 0x22FFFFFF);
            }

            if (i >= list.size()) {
                this.fontRenderer.drawString(I18n.format("gui.jiezhimod.laser.empty"),
                        textX, rowY + 2, COLOR_FAINT);
                this.fontRenderer.drawString(I18n.format("gui.jiezhimod.laser.empty.hint"),
                        textX, rowY + 13, COLOR_FAINT);
                continue;
            }

            LaserInfo lab = list.get(i);
            int color = kindColor(lab.kind);
            boolean alive = lab.alive && lab.kind != LaserInfo.KIND_NONE;

            // 状态方块：开着 = 类型色，关着 = 深灰（比写字省地方，一眼能扫）
            GuiDraw.drawRect(textX, rowY + 3, textX + 5, rowY + 8,
                    alive && lab.on ? color : 0xFF2A3038);

            String head = (lab.label == null || lab.label.isEmpty() ? "—" : lab.label)
                    + " " + LaserLayout.kindName(lab.kind);
            this.fontRenderer.drawString(
                    this.fontRenderer.trimStringToWidth(head, rightX - textX - 10),
                    textX + 10, rowY + 2, alive ? color : COLOR_OFFLINE);

            // 第二行：第一条读数（名称 + 数值），后面跟一条细数据条
            String value;
            double ratio = -1.0D;
            int lineWarn = LaserInfo.WARN_NONE;
            if (!alive) {
                value = I18n.format("gui.jiezhimod.laser.offline");
            } else if (lab.metrics.isEmpty()) {
                value = "--";
            } else {
                LaserInfo.LaserMetric m = lab.metrics.get(0);
                value = m.name + " " + m.value;
                ratio = m.ratio;
                lineWarn = m.warn;
            }
            int valueColor = lineWarn >= LaserInfo.WARN_YELLOW
                    ? LaserLayout.warnColor(lineWarn)
                    : (alive ? 0xFFB9C6D2 : COLOR_OFFLINE);
            int valueRight = rightX;
            if (ratio >= 0.0D) {
                // 细数据条：宽 40、高 3，贴在数值右边
                int barX = rightX - 40;
                GuiDraw.drawRect(barX, rowY + 17, rightX, rowY + 20, 0xFF1E242C);
                int fill = (int) Math.round(40 * Math.max(0.0D, Math.min(1.0D, ratio)));
                if (fill > 0) {
                    GuiDraw.drawRect(barX, rowY + 17, barX + fill, rowY + 20,
                            lineWarn >= LaserInfo.WARN_YELLOW
                                    ? LaserLayout.warnColor(lineWarn) : 0xFF3E9BC4);
                }
                valueRight = barX - 4;
            }
            this.fontRenderer.drawString(
                    this.fontRenderer.trimStringToWidth(value, Math.max(20, valueRight - (textX + 10))),
                    textX + 10, rowY + 13, valueColor);

            // 有"该换透镜 / 冷凝胶不够"这种事：在行尾挂一个感叹号
            if (lab.hasWarning()) {
                this.fontRenderer.drawString("!",
                        rightX - 3, rowY + 2, LaserLayout.warnColor(lab.warn));
            }
        }
    }

    /**
     * 类型 → 列表里的颜色。
     *
     * 【为什么不用 {@link LaserLayout#nameColor(int, boolean, boolean)}】
     * 那个要额外知道"这台算冷却类还是加热类"，那是服务端读 HBM 字段才能判断的；
     * 列表里只需要四种主角色的区分，剩下的一律按"其它"处理。
     */
    private static int kindColor(int kind) {
        switch (kind) {
            case LaserInfo.KIND_SL: return LaserLayout.C_SL;
            case LaserInfo.KIND_PL: return LaserLayout.C_PL;
            case LaserInfo.KIND_OL: return LaserLayout.C_OL;
            case LaserInfo.KIND_INJ: return LaserLayout.C_INJ;
            default: return LaserLayout.C_OTHER;
        }
    }

    /** 底部提示：最多 2 行，且必须在玩家背包上方结束 */
    private void drawHint(int px, int py) {
        List<String> hint = this.fontRenderer.listFormattedStringToWidth(
                I18n.format("gui.jiezhimod.laser.hint", LaserLayout.SLOTS), W - MARGIN * 2);
        for (int i = 0; i < hint.size() && i < 2; i++) {
            this.fontRenderer.drawString(hint.get(i), px + MARGIN,
                    py + ContainerLaserScreen.HINT_Y + i * 9, 0xFF777777);
        }
    }

    private void drawPlayerInventory(int px, int py) {
        int ix = px + ContainerLaserScreen.INV_X;
        int iy = py + ContainerLaserScreen.INV_Y;
        GuiDraw.drawRoundedRect(ix - 3, iy - 3, 9 * 18 + 5,
                (ContainerLaserScreen.HOTBAR_Y - ContainerLaserScreen.INV_Y) + 18 + 5,
                3.0F, 0x33000000);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slotFill(ix + col * 18, iy + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            slotFill(ix + col * 18, py + ContainerLaserScreen.HOTBAR_Y);
        }
    }

    private void slotFill(int x, int y) {
        GuiDraw.drawRect(x, y, x + SLOT_BOX, y + SLOT_BOX, 0xFF15181F);
        GuiDraw.drawRect(x, y, x + SLOT_BOX, y + 1, 0xFF2A3340);
        GuiDraw.drawRect(x, y, x + 1, y + SLOT_BOX, 0xFF2A3340);
    }

    private void drawSlotBox(int x, int y) {
        slotFill(x, y);
        // 链接器槽描一圈亮边，一眼能认出"这是要往里放东西的地方"
        GuiDraw.drawRect(x, y + SLOT_BOX - 1, x + SLOT_BOX, y + SLOT_BOX, 0xFF3C5A78);
        GuiDraw.drawRect(x + SLOT_BOX - 1, y, x + SLOT_BOX, y + SLOT_BOX, 0xFF3C5A78);
    }
}
