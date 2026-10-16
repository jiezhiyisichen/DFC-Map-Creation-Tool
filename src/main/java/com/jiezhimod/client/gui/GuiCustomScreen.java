package com.jiezhimod.client.gui;

import com.jiezhimod.block.ContainerCustomScreen;
import com.jiezhimod.block.TileCustomScreen;
import com.jiezhimod.client.util.GuiDraw;
import com.jiezhimod.dfc.DfcWatch;
import com.jiezhimod.item.ItemDfcRemote;
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
import java.util.List;

/**
 * 自定义显示屏的设置界面（**右键方块打开**，这是这块屏与另外两块屏最大的行为差别）。
 *
 * 界面分四段：
 *   1. 开机 / 关机按钮；
 *   2. **遥控器槽位** + 「添加」按钮 —— 把 DFC 遥控器放进槽位，点一下，
 *      遥控器上记录到的设备就全部接进来（这是本次改动的核心）；
 *   3. 8 行的数据列表，每行是"设备 · 数据名 = 读数"，右侧一个"删除"按钮；
 *   4. 玩家背包 + 底部：清空 / 关闭。
 *
 * 【为什么从 GuiScreen 换成 GuiContainer】
 * 需求变成了"把遥控器**放进**一个槽位"。拖放、Shift 点击都是原版物品槽的机制，
 * 自己拿 GuiScreen 画个假格子是拖不进去的 —— 必须用真正的 {@code Slot} + {@code Container}。
 *
 * 【为什么所有东西都画在背景层】
 * 1.12.2 里 {@code GuiContainer.drawScreen} 是**先**调
 * {@code drawGuiContainerBackgroundLayer}（此时**没有** translate，坐标 = 屏幕绝对坐标），
 * **之后**才 {@code translate(guiLeft, guiTop)} 再调 foreground 层（坐标 = 面板相对坐标）。
 * 两层坐标系不一样，混着画最容易出"文本偏了半屏"这种低级错。
 * 所以这里干脆**全部画在背景层、一律用绝对坐标**（{@code px + 偏移}），foreground 层留空。
 * 物品图标由 GuiContainer 自己在更后面画，所以图标天然压在底纹之上，不需要额外处理。
 *
 * 【列表内容从哪来】客户端方块实体里同步过来的镜像（{@code displaySlots()}）。
 * 这里**只读不写** —— 删除 / 开机关机 / 添加 都发 {@link MessageCustomScreenAction}
 * 给服务端，服务端改完广播回来，界面下一帧自己就变了。
 */
@SideOnly(Side.CLIENT)
public class GuiCustomScreen extends GuiContainer {

    // 版面尺寸与槽位坐标**全部引用容器里那一份常量**，两边不会错位
    private static final int W = ContainerCustomScreen.PANEL_W;
    private static final int H = ContainerCustomScreen.PANEL_H;
    private static final int MARGIN = 12;

    private static final int LIST_TOP = 64;
    private static final int ROW_H = 14;
    private static final int DEL_W = 36;
    private static final int SLOT_BOX = 18;

    private static final int ID_POWER = 0;
    private static final int ID_CLEAR = 1;
    private static final int ID_CLOSE = 2;
    private static final int ID_ADD = 3;
    /** 每行"删除"按钮的 id = ID_DEL_BASE + 行号 */
    private static final int ID_DEL_BASE = 100;

    private final TileCustomScreen tile;
    private final BlockPos pos;
    private GuiButton powerButton;
    private GuiButton clearButton;
    private GuiButton addButton;
    private GuiButton[] delButtons = new GuiButton[DfcWatch.MAX];

    public GuiCustomScreen(TileCustomScreen tile, ContainerCustomScreen container) {
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

    @Override
    public void initGui() {
        super.initGui();   // 这里才算出 guiLeft / guiTop
        int px = this.guiLeft;
        int py = this.guiTop;

        this.powerButton = new GuiButton(ID_POWER, px + MARGIN, py + 20, 90, 16, "");
        this.buttonList.add(this.powerButton);

        // 「添加」按钮就摆在遥控器槽位右边 —— 眼睛从槽位扫到按钮是一条直线
        this.addButton = new GuiButton(ID_ADD,
                px + ContainerCustomScreen.REMOTE_SLOT_X + 78,
                py + ContainerCustomScreen.REMOTE_SLOT_Y + 1, 60, 16,
                I18n.format("gui.jiezhimod.custom.add"));
        this.buttonList.add(this.addButton);

        for (int i = 0; i < DfcWatch.MAX; i++) {
            GuiButton b = new GuiButton(ID_DEL_BASE + i,
                    px + W - MARGIN - DEL_W, py + LIST_TOP + i * ROW_H + 1, DEL_W, 12,
                    I18n.format("gui.jiezhimod.custom.delete"));
            this.delButtons[i] = b;
            this.buttonList.add(b);
        }

        this.clearButton = new GuiButton(ID_CLEAR, px + MARGIN, py + 196, 76, 16,
                I18n.format("gui.jiezhimod.custom.clear"));
        this.buttonList.add(this.clearButton);
        this.buttonList.add(new GuiButton(ID_CLOSE, px + W / 2 - 35, py + 196, 70, 16,
                I18n.format("gui.jiezhimod.custom.close")));

        refreshButtons();
    }

    /**
     * 按钮状态跟着数据走：开机键的字样、每行"删除"是否可用、"添加"是否可用。
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
        if (this.addButton != null) {
            // 没放遥控器时按钮是灰的 —— 否则玩家点一下没反应，只会以为坏了
            this.addButton.enabled = remoteInSlot() != null;
        }
    }

    private List<DfcWatch> slots() {
        return this.tile.displaySlots();
    }

    /** 槽位里的遥控器（不是遥控器或空着都返回 null） */
    private ItemStack remoteInSlot() {
        if (this.inventorySlots == null || this.inventorySlots.getSlot(0) == null) {
            return null;
        }
        ItemStack s = this.inventorySlots.getSlot(0).getStack();
        if (s == null || s.isEmpty() || !(s.getItem() instanceof ItemDfcRemote)) {
            return null;
        }
        return s;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
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
        if (id == ID_ADD) {
            send(MessageCustomScreenAction.ACT_IMPORT, 0);
            return;
        }
        if (id >= ID_DEL_BASE && id < ID_DEL_BASE + DfcWatch.MAX) {
            send(MessageCustomScreenAction.ACT_DELETE, id - ID_DEL_BASE);
        }
    }

    private void send(int action, int index) {
        ModNetwork.CHANNEL.sendToServer(new MessageCustomScreenAction(this.pos, action, index));
    }

    /**
     * 关界面。
     *
     * 【为什么必须是 closeScreen() 而不是只 displayGuiScreen(null)】
     * {@code EntityPlayerSP.closeScreen()} 才会给服务端发关闭窗口的包，
     * 服务端收到才会走 {@code ContainerCustomScreen.onContainerClosed} 把遥控器还给玩家。
     * 只关界面不发包的话，槽里那个遥控器就"卡"在客户端这边了。
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

        GuiDraw.drawRoundedRect(px, py, W, H, 8.0F, 0xEE0A0A0F);

        // 标题（居中）
        String title = I18n.format("gui.jiezhimod.custom.title");
        this.fontRenderer.drawString(title, px + (W - this.fontRenderer.getStringWidth(title)) / 2,
                py + 6, 0xFFFFFFFF);

        // 开机按钮右侧的状态提示
        this.fontRenderer.drawString(
                I18n.format(this.tile.isPowered()
                        ? "gui.jiezhimod.custom.state.on" : "gui.jiezhimod.custom.state.off"),
                px + MARGIN + 96, py + 24,
                this.tile.isPowered() ? 0xFF52E08A : 0xFF888888);

        drawRemoteRow(px, py);
        drawList(px, py);
        drawPlayerInventory(px, py);

        // 提示文字**自动折行**：中英长度差一倍多（"放入遥控器"vs"Insert the remote"），
        // 写死一行必然在一种语言下冲出面板。只用 2 行空间，5px 行距正好塞得下。
        List<String> hint = this.fontRenderer.listFormattedStringToWidth(
                I18n.format("gui.jiezhimod.custom.hint"), W - MARGIN * 2);
        for (int i = 0; i < hint.size() && i < 2; i++) {
            this.fontRenderer.drawString(hint.get(i), px + MARGIN, py + 182 + i * 10, 0xFF777777);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 故意留空：全部画在背景层（绝对坐标），见类注释
    }

    /** 遥控器槽位那一行：槽底 + 标签 + 遥控器里记录了几台 */
    private void drawRemoteRow(int px, int py) {
        int sx = px + ContainerCustomScreen.REMOTE_SLOT_X;
        int sy = py + ContainerCustomScreen.REMOTE_SLOT_Y;
        drawSlotBox(sx, sy);

        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.custom.remote"),
                sx + SLOT_BOX + 6, sy + 5, 0xFF9AA7B4);

        ItemStack remote = remoteInSlot();
        String text;
        int color;
        if (remote == null) {
            text = I18n.format("gui.jiezhimod.custom.remote.empty");
            color = 0xFF6A7480;
        } else {
            text = I18n.format("gui.jiezhimod.custom.remote.count",
                    ItemDfcRemote.deviceCount(remote), ItemDfcRemote.MAX_DEVICES);
            color = ItemDfcRemote.deviceCount(remote) > 0 ? 0xFF7FE3A0 : 0xFFD8B25A;
        }
        // 右对齐贴着面板右边 —— 长句也不会顶出面板
        this.fontRenderer.drawString(text, px + W - MARGIN - this.fontRenderer.getStringWidth(text),
                sy + 5, color);
    }

    /** 数据列表：8 行，每行 序号 / 设备·数据名 / 读数，右侧是"删除"按钮 */
    private void drawList(int px, int py) {
        List<DfcWatch> list = slots();
        int textX = px + MARGIN;
        int delX = px + W - MARGIN - DEL_W;

        for (int i = 0; i < DfcWatch.MAX; i++) {
            int rowY = py + LIST_TOP + i * ROW_H;
            // 隔行深浅底：8 行挤在一起，没有斑马纹很容易看串行
            if ((i & 1) == 0) {
                GuiDraw.drawRoundedRect(px + MARGIN - 2, rowY - 1, W - MARGIN * 2 + 4,
                        ROW_H, 2.0F, 0x22FFFFFF);
            }
            this.fontRenderer.drawString((i + 1) + ".", textX, rowY + 3, 0xFF666666);

            if (i >= list.size()) {
                this.fontRenderer.drawString(I18n.format("gui.jiezhimod.custom.empty"),
                        textX + 14, rowY + 3, 0xFF444444);
                continue;
            }

            DfcWatch w = list.get(i);
            String left = w.device + " · " + (w.name == null || w.name.isEmpty() ? "--" : w.name);
            if (left.length() > 20) {
                left = left.substring(0, 20) + "…";
            }
            this.fontRenderer.drawString(left, textX + 14, rowY + 3,
                    w.alive ? 0xFFCCCCCC : 0xFF995555);

            String value = w.alive ? w.value : I18n.format("gui.jiezhimod.custom.offline");
            // 数值右对齐贴着"删除"按钮左边，长读数（"1.23 GHE / 2.50 GHE"）也放得下
            int vx = delX - 6 - this.fontRenderer.getStringWidth(value);
            this.fontRenderer.drawString(value, Math.max(textX + 110, vx), rowY + 3,
                    w.alive ? 0xFF48D6FF : 0xFF995555);
        }
    }

    /**
     * 玩家背包 + 快捷栏的格子底纹。
     *
     * 先用一块整底把这一片压暗，再逐格画小方块 —— 只画 36 个方块的话，
     * 格子之间的间隙会透出面板底色，看起来像"缺了几格"。
     */
    private void drawPlayerInventory(int px, int py) {
        int ix = px + ContainerCustomScreen.INV_X;
        int iy = py + ContainerCustomScreen.INV_Y;
        GuiDraw.drawRoundedRect(ix - 3, iy - 3, 9 * 18 + 5, (ContainerCustomScreen.HOTBAR_Y
                - ContainerCustomScreen.INV_Y) + 18 + 5, 3.0F, 0x33000000);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slotFill(ix + col * 18, iy + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            slotFill(ix + col * 18, py + ContainerCustomScreen.HOTBAR_Y);
        }
    }

    /** 一格物品槽：深底 + 上/左各一条暗边（够看清格子边界，又不像贴图那么抢眼） */
    private void slotFill(int x, int y) {
        GuiDraw.drawRect(x, y, x + SLOT_BOX, y + SLOT_BOX, 0xFF15181F);
        GuiDraw.drawRect(x, y, x + SLOT_BOX, y + 1, 0xFF2A3340);
        GuiDraw.drawRect(x, y, x + 1, y + SLOT_BOX, 0xFF2A3340);
    }

    private void drawSlotBox(int x, int y) {
        slotFill(x, y);
        // 遥控器槽再描一圈亮边，一眼能认出"这是要往里放东西的地方"
        GuiDraw.drawRect(x, y + SLOT_BOX - 1, x + SLOT_BOX, y + SLOT_BOX, 0xFF3C5A78);
        GuiDraw.drawRect(x + SLOT_BOX - 1, y, x + SLOT_BOX, y + SLOT_BOX, 0xFF3C5A78);
    }
}
