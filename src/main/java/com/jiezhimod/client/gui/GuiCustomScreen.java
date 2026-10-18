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
 * 版面分两区（384 宽的两栏在 6 倍 GUI 缩放下会溢出，所以只分左右两块、不做双列列表）：
 *   · **左区**：开机 / 关机按钮、状态、**遥控器槽位**、「添加」按钮；
 *   · **右区**：8 行数据列表，每行"序号 / 设备·数据名 / 读数"，右侧一个"删除"按钮；
 *   · **底部**：提示、清空 / 关闭、玩家背包。
 *
 * 【为什么从 GuiScreen 换成 GuiContainer】
 * 需求是"把遥控器**放进**一个槽位"。拖放、Shift 点击都是原版物品槽的机制，
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
 * 【为什么面板底色几乎不透明】
 * 这块屏在世界里本身就画着"尚未接入数据 / 右键本屏打开面板"的引导，
 * 面板半透明时那几行字会从背后透出来、和列表叠在一起，读不清。
 * 所以底纹用 0xFB（98%）而不是早些时候的 0xEE。
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
    private static final int MARGIN = ContainerCustomScreen.MARGIN;

    private static final int SLOT_BOX = 18;
    /** 行文字到"删除"按钮之间的留白 */
    private static final int VALUE_GAP = 6;

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

    /** 排错用：只在第一次进 initGui / 第一次画背景层时各打一行，不刷屏 */
    private static boolean loggedInit = false;
    private static boolean loggedBg = false;

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

        this.powerButton = new GuiButton(ID_POWER, px + MARGIN, py + ContainerCustomScreen.POWER_Y,
                ContainerCustomScreen.POWER_W, 16, "");
        this.buttonList.add(this.powerButton);

        this.addButton = new GuiButton(ID_ADD, px + ContainerCustomScreen.ADD_BTN_X,
                py + ContainerCustomScreen.ADD_BTN_Y, ContainerCustomScreen.ADD_BTN_W, 16,
                I18n.format("gui.jiezhimod.custom.add"));
        this.buttonList.add(this.addButton);

        int delX = px + W - MARGIN - ContainerCustomScreen.DEL_W;
        for (int i = 0; i < DfcWatch.MAX; i++) {
            GuiButton b = new GuiButton(ID_DEL_BASE + i, delX,
                    py + ContainerCustomScreen.LIST_TOP + i * ContainerCustomScreen.ROW_H,
                    ContainerCustomScreen.DEL_W, ContainerCustomScreen.DEL_H,
                    I18n.format("gui.jiezhimod.custom.delete"));
            this.delButtons[i] = b;
            this.buttonList.add(b);
        }

        this.clearButton = new GuiButton(ID_CLEAR, px + MARGIN, py + ContainerCustomScreen.BTN_ROW_Y,
                ContainerCustomScreen.CLEAR_W, 16, I18n.format("gui.jiezhimod.custom.clear"));
        this.buttonList.add(this.clearButton);
        this.buttonList.add(new GuiButton(ID_CLOSE, px + ContainerCustomScreen.CLOSE_X,
                py + ContainerCustomScreen.BTN_ROW_Y, ContainerCustomScreen.CLOSE_W, 16,
                I18n.format("gui.jiezhimod.custom.close")));

        if (!loggedInit) {
            loggedInit = true;
            com.jiezhimod.JiezhiMod.warn("[GuiCustomScreen] init: screen={}x{} panel={}x{} "
                            + "gui=({},{}) overflowY={} overflowX={}",
                    this.width, this.height, W, H, px, py,
                    (py < 0 || py + H > this.height), (px < 0 || px + W > this.width));
        }

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

        if (!loggedBg) {
            loggedBg = true;
            com.jiezhimod.JiezhiMod.warn("[GuiCustomScreen] bg layer: gui=({},{}) screen={}x{} "
                            + "panel={}x{} overflow={}",
                    px, py, this.width, this.height, W, H,
                    (py < 0 || py + H > this.height || px < 0 || px + W > this.width));
        }

        // 完全不透明：这块屏在世界里本身就画着「尚未接入数据」的引导，
        // 只要留一点透明，那几行字就会从背后透出来和列表叠在一起。
        GuiDraw.drawRoundedRect(px, py, W, H, 8.0F, 0xFF0B0B10);

        drawTitle(px, py);
        drawPowerRow(px, py);
        drawRemoteRow(px, py);
        drawAddHint(px, py);
        drawList(px, py);
        drawHint(px, py);
        drawPlayerInventory(px, py);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 故意留空：全部画在背景层（绝对坐标），见类注释
    }

    private void drawTitle(int px, int py) {
        String title = I18n.format("gui.jiezhimod.custom.title");
        this.fontRenderer.drawString(title, px + (W - this.fontRenderer.getStringWidth(title)) / 2,
                py + ContainerCustomScreen.TITLE_Y, 0xFFFFFFFF);
    }

    /** 左区第一行：开机/关机按钮（按钮本身是 GuiButton，这里只画它右边的状态文字） */
    private void drawPowerRow(int px, int py) {
        this.fontRenderer.drawString(
                I18n.format(this.tile.isPowered()
                        ? "gui.jiezhimod.custom.state.on" : "gui.jiezhimod.custom.state.off"),
                px + MARGIN, py + ContainerCustomScreen.STATE_Y,
                this.tile.isPowered() ? 0xFF52E08A : 0xFF888888);
    }

    /**
     * 遥控器槽位那一行：槽底 + 「遥控器」标签 + 计数。
     *
     * 【为什么计数紧跟标签、不再右对齐到面板右边】右对齐会把一串长文案横穿到右区的
     * 数据列表上（旧版面里它甚至盖住了「添加」按钮，玩家只看到半句残字）。
     * 现在它只在左区内排版，宽度不够就按像素裁 —— 宁可截断，也不许压到别的控件。
     */
    private void drawRemoteRow(int px, int py) {
        int sx = px + ContainerCustomScreen.REMOTE_SLOT_X;
        int sy = py + ContainerCustomScreen.REMOTE_SLOT_Y;
        drawSlotBox(sx, sy);

        String label = I18n.format("gui.jiezhimod.custom.remote");
        int lx = px + ContainerCustomScreen.REMOTE_LABEL_X;
        int ly = py + ContainerCustomScreen.REMOTE_LABEL_Y;
        this.fontRenderer.drawString(label, lx, ly, 0xFF9AA7B4);

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
        int tx = lx + this.fontRenderer.getStringWidth(label) + 6;
        // 左边只能用到 LIST_X 之前，超出按像素裁（不压到右区列表）
        int maxW = Math.max(12, (px + ContainerCustomScreen.LIST_X - 4) - tx);
        this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(text, maxW), tx, ly, color);
    }

    /** 「添加」按钮下方那句短提示（按钮本身是 GuiButton） */
    private void drawAddHint(int px, int py) {
        int hx = px + ContainerCustomScreen.ADD_HINT_X;
        // 同样只在左区内排版：右边是数据列表，不许压过去
        int maxW = Math.max(12, (px + ContainerCustomScreen.LIST_X - 4) - hx);
        this.fontRenderer.drawString(
                this.fontRenderer.trimStringToWidth(
                        I18n.format("gui.jiezhimod.custom.add.hint"), maxW),
                hx, py + ContainerCustomScreen.ADD_HINT_Y, 0xFF6A7480);
    }

    /** 右区数据列表：8 行，每行 序号 / 设备·数据名 / 读数，右侧是"删除"按钮 */
    private void drawList(int px, int py) {
        List<DfcWatch> list = slots();
        int textX = px + ContainerCustomScreen.LIST_X;
        int delX = px + W - MARGIN - ContainerCustomScreen.DEL_W;
        // 读数右对齐到这里；名字区就是 textX .. valueRight
        int valueRight = delX - VALUE_GAP;

        for (int i = 0; i < DfcWatch.MAX; i++) {
            int rowY = py + ContainerCustomScreen.LIST_TOP + i * ContainerCustomScreen.ROW_H;
            // 隔行深浅底：8 行挤在一起，没有斑马纹很容易看串行
            if ((i & 1) == 0) {
                GuiDraw.drawRoundedRect(textX - 4, rowY - 1,
                        (px + W - MARGIN) - (textX - 4), ContainerCustomScreen.ROW_H,
                        2.0F, 0x22FFFFFF);
            }
            this.fontRenderer.drawString((i + 1) + ".", textX, rowY + 2, 0xFF666666);

            if (i >= list.size()) {
                this.fontRenderer.drawString(I18n.format("gui.jiezhimod.custom.empty"),
                        textX + 12, rowY + 2, 0xFF444444);
                continue;
            }

            DfcWatch w = list.get(i);
            String value = w.alive ? w.value : I18n.format("gui.jiezhimod.custom.offline");
            int valueW = this.fontRenderer.getStringWidth(value);
            int valueX = valueRight - valueW;

            // 名字按**像素**裁，裁到还剩多少算多少 —— 早先按"20 个字符"裁，
            // 汉字是等宽英文的两倍宽，长名字照样会压到右边的读数上。
            int nameW = Math.max(8, valueX - VALUE_GAP - (textX + 12));
            String left = w.device + " · " + (w.name == null || w.name.isEmpty() ? "--" : w.name);
            this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(left, nameW),
                    textX + 12, rowY + 2, w.alive ? 0xFFCCCCCC : 0xFF995555);

            this.fontRenderer.drawString(value, Math.max(textX + 12, valueX), rowY + 2,
                    w.alive ? 0xFF48D6FF : 0xFF995555);
        }
    }

    /**
     * 底部提示：**只画 2 行**，且整块必须在底部按钮**上方**结束。
     *
     * 旧版面把提示放在 y=182、按钮放在 y=196，第 2 行的下半截正好被按钮盖住
     * （玩家看到的是半个「条)」）。现在提示区高度固定为 2 行 × 9px = 18，
     * 且 HINT_Y + 18 &lt;= BTN_ROW_Y，SelfTest 有断言。
     */
    private void drawHint(int px, int py) {
        List<String> hint = this.fontRenderer.listFormattedStringToWidth(
                I18n.format("gui.jiezhimod.custom.hint"), W - MARGIN * 2);
        for (int i = 0; i < hint.size() && i < 2; i++) {
            this.fontRenderer.drawString(hint.get(i), px + MARGIN,
                    py + ContainerCustomScreen.HINT_Y + i * 9, 0xFF777777);
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
