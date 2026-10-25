package com.jiezhimod.client.gui;

import com.jiezhimod.block.BlockCustomScreen;
import com.jiezhimod.block.BlockDfcGraph;
import com.jiezhimod.block.BlockDfcMonitor;
import com.jiezhimod.block.BlockDfcScreen;
import com.jiezhimod.block.ContainerDebug;
import com.jiezhimod.block.TileDfcScreen;
import com.jiezhimod.block.TileKeypad;
import com.jiezhimod.block.TileLabelScreen;
import com.jiezhimod.dfc.DfcSnapshot;
import com.jiezhimod.dfc.LabelLayout;
import com.jiezhimod.network.msg.MessageLabelConfig;
import com.jiezhimod.client.util.GuiDraw;
import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageDebugConfig;
import com.jiezhimod.network.msg.MessageKeypadConfig;
import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;

/**
 * 调试界面（调试棒蹲下右键本模组方块打开）。
 *
 * 两类可调项：
 *
 * 一、**显示器**：屏幕刷新率。默认 50ms（20Hz），但每块屏每帧都要重画 768×576 画布
 *   再整张上传纹理 —— 四块一起开时开销很可观（玩家实测 9 FPS）。
 *   推荐值按设备给：核心屏 50 / 部件页 100 / 自定义屏 200 / 曲线屏 200。
 *
 * 二、**标注显示器**：七个标注格各自显示哪个指标（温度 / 发电量 / 坍缩度 …）。
 *   每格一对箭头循环切换，右边同时预览"这一格现在会显示什么读数"。
 *
 * 三、**密码盘**：便签纸文字（最多 8 个字符）、密码模式（随机 / 固定）。
 *   · 便签文字留空 = 便签上直接显示当前密码（默认行为）；
 *   · 固定密码必须是 6 位纯数字，否则服务端会把它降级回随机并提示。
 *
 * 【状态来源】当前值读方块实体的客户端镜像（服务端广播回来的）；
 * 按下按钮才发包，不本地改 —— 服务端拒绝时界面不会显示假选中。
 *
 * 【坐标系】本项目约定：**所有绘制都在背景层、一律用屏幕绝对坐标**，前景层留空。
 * 文字输入框（GuiTextField）不属于「绘制」，必须在 drawScreen 里单独喂一次。
 */
public class GuiDebug extends GuiContainer {

    private static final int W = 248;
    private static final int H = 150;
    private static final int BTN_W = 68;
    private static final int BTN_H = 16;
    private static final int ID_CHOICE_BASE = 0;
    private static final int ID_CLOSE = 100;
    private static final int ID_MODE = 101;
    private static final int ID_SAVE = 102;
    private static final int ID_LABEL_RESET = 103;
    private static final int ID_LABEL_REFRESH = 104;
    /** 标注屏那半页的面板尺寸（七行 + 页脚） */
    private static final int W_LABEL = 250;
    private static final int H_LABEL = 206;
    /**
     * 标注格按钮的 ID 基址：第 i 格用 {@code BASE + i*2}（左箭头）与
     * {@code BASE + i*2 + 1}（右箭头）。与刷新率那批（0~4）拉开足够距离。
     */
    private static final int ID_LABEL_ARROW = 200;
    /** 七行箭头的起始 y（相对面板）与行距 */
    private static final int LABEL_ROW_TOP = 44;
    private static final int LABEL_ROW_H = 20;

    private final BlockPos pos;
    private final TileDfcScreen screen;   // 可能为 null（不是显示器）
    private final TileKeypad keypad;      // 可能为 null（不是密码盘）
    /** 标注显示器专用：七格指标配置（是 screen 的子类，两者会同时非空） */
    private final TileLabelScreen label;

    /** 便签文字输入框（只有密码盘才建） */
    private GuiTextField noteField;
    /** 固定密码输入框（只有密码盘才建） */
    private GuiTextField passField;
    /** 界面上暂存的模式：点「保存」才生效，中途切换不影响世界里的密码 */
    private int editMode;

    public GuiDebug(BlockPos pos, ContainerDebug container) {
        super(container);
        this.pos = pos;
        this.xSize = W;
        this.ySize = H;

        TileEntity te = null;
        if (net.minecraft.client.Minecraft.getMinecraft().world != null) {
            te = net.minecraft.client.Minecraft.getMinecraft().world.getTileEntity(pos);
        }
        this.label = te instanceof TileLabelScreen ? (TileLabelScreen) te : null;
        this.screen = te instanceof TileDfcScreen ? (TileDfcScreen) te : null;
        this.keypad = te instanceof TileKeypad ? (TileKeypad) te : null;
        // 标注屏要放七行箭头，比刷新率那半页高不少 —— 面板尺寸按模式走
        if (this.label != null) {
            this.xSize = W_LABEL;
            this.ySize = H_LABEL;
        }
        this.editMode = this.keypad == null ? TileKeypad.MODE_RANDOM : this.keypad.getPasswordMode();
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

        if (this.label != null) {
            // 七行：每行一对箭头，中间是"指标名 …… 当前读数"（名字由背景层画）
            for (int i = 0; i < LabelLayout.SLOTS; i++) {
                int y = py + LABEL_ROW_TOP + i * LABEL_ROW_H;
                this.buttonList.add(new GuiButton(ID_LABEL_ARROW + i * 2,
                        px + 8, y, 14, 16, "<"));
                this.buttonList.add(new GuiButton(ID_LABEL_ARROW + i * 2 + 1,
                        px + W_LABEL - 22, y, 14, 16, ">"));
            }
            this.buttonList.add(new GuiButton(ID_LABEL_REFRESH,
                    px + 8, py + H_LABEL - 24, 76, 16, ""));
            this.buttonList.add(new GuiButton(ID_LABEL_RESET,
                    px + 88, py + H_LABEL - 24, 76, 16,
                    I18n.format("gui.jiezhimod.debug.label.reset")));
            this.buttonList.add(new GuiButton(ID_CLOSE,
                    px + 168, py + H_LABEL - 24, 74, 16,
                    I18n.format("gui.jiezhimod.debug.close")));
            refreshButtons();
            return;
        }

        if (this.keypad != null) {
            this.noteField = new GuiTextField(1, this.fontRenderer, px + 14, py + 50, 220, 16);
            this.noteField.setMaxStringLength(TileKeypad.NOTE_MAX);
            this.noteField.setText(this.keypad.getNoteText());

            this.passField = new GuiTextField(2, this.fontRenderer, px + 14, py + 84, 104, 16);
            this.passField.setMaxStringLength(TileKeypad.PASSWORD_LEN);
            this.passField.setText(this.keypad.getFixedPassword());

            this.buttonList.add(new GuiButton(ID_MODE, px + 124, py + 84, 110, BTN_H, ""));
            this.buttonList.add(new GuiButton(ID_SAVE, px + 14, py + 122, 105, BTN_H,
                    I18n.format("gui.jiezhimod.debug.save")));
            this.buttonList.add(new GuiButton(ID_CLOSE, px + 129, py + 122, 105, BTN_H,
                    I18n.format("gui.jiezhimod.debug.close")));
            refreshButtons();
            return;
        }

        if (this.screen != null) {
            int[] choices = TileDfcScreen.REFRESH_CHOICES_MS;
            for (int i = 0; i < choices.length; i++) {
                int col = i % 3;
                int row = i / 3;
                this.buttonList.add(new GuiButton(ID_CHOICE_BASE + i,
                        px + 14 + col * (BTN_W + 10), py + 50 + row * 20,
                        BTN_W, BTN_H, ""));
            }
            refreshButtons();
        }

        this.buttonList.add(new GuiButton(ID_CLOSE, px + W / 2 - 32, py + 120, 64, BTN_H,
                I18n.format("gui.jiezhimod.debug.close")));
    }

    private void refreshButtons() {
        if (this.label != null) {
            GuiButton rb = findButton(ID_LABEL_REFRESH);
            if (rb != null) {
                // 刷新率也放在这一页：标注屏同样每帧重画 768x576，
                // 屏多的时候调慢它比什么都管用
                rb.displayString = this.screen == null ? ""
                        : I18n.format("gui.jiezhimod.debug.label.refresh", this.screen.getRefreshMs());
            }
            return;
        }
        if (this.keypad != null) {
            GuiButton mode = findButton(ID_MODE);
            if (mode != null) {
                mode.displayString = I18n.format(this.editMode == TileKeypad.MODE_FIXED
                        ? "gui.jiezhimod.debug.keypad.mode.fixed"
                        : "gui.jiezhimod.debug.keypad.mode.random");
            }
            return;
        }
        if (this.screen == null) {
            return;
        }
        int[] choices = TileDfcScreen.REFRESH_CHOICES_MS;
        int cur = this.screen.getRefreshMs();
        for (int i = 0; i < choices.length; i++) {
            GuiButton b = findButton(ID_CHOICE_BASE + i);
            if (b != null) {
                b.displayString = (choices[i] == cur ? "▶ " : "· ") + choices[i] + " ms";
            }
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

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.passField != null) {
            // 密码框只收数字：粘贴进来的其它字符就地剔掉（服务端还会再校验一次）
            String t = this.passField.getText();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < t.length() && sb.length() < TileKeypad.PASSWORD_LEN; i++) {
                char c = t.charAt(i);
                if (c >= '0' && c <= '9') {
                    sb.append(c);
                }
            }
            if (!sb.toString().equals(t)) {
                this.passField.setText(sb.toString());
            }
        }
        refreshButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == ID_CLOSE) {
            this.mc.displayGuiScreen(null);
            return;
        }
        if (button.id == ID_MODE) {
            this.editMode = this.editMode == TileKeypad.MODE_FIXED
                    ? TileKeypad.MODE_RANDOM : TileKeypad.MODE_FIXED;
            refreshButtons();
            return;
        }
        if (button.id == ID_SAVE) {
            this.editMode = save();
            refreshButtons();
            return;
        }
        if (this.label != null) {
            if (button.id == ID_LABEL_RESET) {
                // 一键恢复出厂：逐格发（服务端逐格应用，最后一块屏也是这个顺序）
                int[] def = LabelLayout.defaults();
                for (int i = 0; i < LabelLayout.SLOTS; i++) {
                    ModNetwork.CHANNEL.sendToServer(new MessageLabelConfig(this.pos, i, def[i]));
                }
                return;
            }
            if (button.id == ID_LABEL_REFRESH && this.screen != null) {
                int[] choices = TileDfcScreen.REFRESH_CHOICES_MS;
                int cur = this.screen.getRefreshMs();
                int idx = 0;
                for (int i = 0; i < choices.length; i++) {
                    if (choices[i] == cur) {
                        idx = i;
                        break;
                    }
                }
                ModNetwork.CHANNEL.sendToServer(
                        new MessageDebugConfig(this.pos, choices[(idx + 1) % choices.length]));
                return;
            }
            int id = button.id - ID_LABEL_ARROW;
            if (id >= 0 && id < LabelLayout.SLOTS * 2) {
                int slot = id / 2;
                int cur = this.label.metricAt(slot);
                // 偶数号 = 左箭头（上一个），奇数号 = 右箭头（下一个）
                int nm = (id % 2) == 1
                        ? LabelLayout.nextMetric(cur) : LabelLayout.prevMetric(cur);
                // 不本地改：等服务端回包。这样"服务端拒绝"时界面不会显示假状态
                ModNetwork.CHANNEL.sendToServer(new MessageLabelConfig(this.pos, slot, nm));
                return;
            }
        }

        if (this.screen != null) {
            int[] choices = TileDfcScreen.REFRESH_CHOICES_MS;
            int idx = button.id - ID_CHOICE_BASE;
            if (idx >= 0 && idx < choices.length) {
                ModNetwork.CHANNEL.sendToServer(new MessageDebugConfig(this.pos, choices[idx]));
            }
        }
    }

    /**
     * 把当前表单发给服务端。
     *
     * @return 发出去之后**界面上应该显示的模式**。
     *         选了固定但密码不是 6 位数字时不发固定，就地退回随机并把提示留给玩家看。
     */
    private int save() {
        String note = this.noteField == null ? "" : this.noteField.getText().trim();
        if (note.length() > TileKeypad.NOTE_MAX) {
            note = note.substring(0, TileKeypad.NOTE_MAX);
        }
        String pass = this.passField == null ? "" : this.passField.getText().trim();
        int mode = this.editMode;
        if (mode == TileKeypad.MODE_FIXED && !TileKeypad.isValidPassword(pass)) {
            // 界面上直接退回随机，别让玩家以为"固定密码已生效"
            mode = TileKeypad.MODE_RANDOM;
        }
        ModNetwork.CHANNEL.sendToServer(new MessageKeypadConfig(this.pos, mode, pass, note));
        return mode;
    }

    // ==================== 绘制（背景层 / 绝对坐标） ====================

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int px = this.guiLeft;
        int py = this.guiTop;

        GuiDraw.drawRoundedRect(px, py, W, H, 8.0F, 0xFF0B0B10);

        String title = I18n.format("gui.jiezhimod.debug.title");
        this.fontRenderer.drawString(title,
                px + (W - this.fontRenderer.getStringWidth(title)) / 2, py + 6, 0xFFFFFFFF);

        Block block = this.mc.world == null ? null : this.mc.world.getBlockState(this.pos).getBlock();
        String name = block == null ? "?" : new ItemStack(block).getDisplayName();
        this.fontRenderer.drawString(clip(I18n.format("gui.jiezhimod.debug.block", name), W - 28),
                px + 14, py + 22, 0xFF9AA7B4);

        if (this.label != null) {
            drawLabelSection(px, py);
            return;
        }

        if (this.keypad != null) {
            drawKeypadSection(px, py);
            return;
        }

        if (this.screen == null) {
            this.fontRenderer.drawString(I18n.format("gui.jiezhimod.debug.nothing"),
                    px + 14, py + 56, 0xFF8FA0B0);
            return;
        }

        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.debug.refresh"),
                px + 14, py + 38, 0xFF8FA0B0);
        String cur = this.screen.getRefreshMs() + " ms";
        this.fontRenderer.drawString(cur,
                px + W - 14 - this.fontRenderer.getStringWidth(cur), py + 38, 0xFF7FE3A0);

        int[] rec = recommend(block);
        if (rec != null) {
            String text = I18n.format("gui.jiezhimod.debug.recommend", rec[0],
                    I18n.format(RECOMMEND_KEY[rec[1]]));
            this.fontRenderer.drawString(clip(text, W - 28), px + 14, py + 94, 0xFFD8B25A);
        }
    }

    /**
     * 标注显示器那半页：七行「&lt; ① 指标名 …… 当前读数 &gt;」。
     *
     * 【为什么把当前读数也画出来】配置界面上只看"温度等级"四个字，
     * 玩家分不清它和"温度"有什么区别；把真实读数摆在右边，
     * 切换指标时立刻能看到"哦，这一格会变成 0.00%"。
     * 读数来自客户端镜像（服务端广播回来的那份快照），不是本地算的。
     */
    private void drawLabelSection(int px, int py) {
        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.debug.label.hint"),
                px + 10, py + 33, 0xFF8FA0B0);

        DfcSnapshot d = this.screen == null ? null : this.screen.getData();
        for (int i = 0; i < LabelLayout.SLOTS; i++) {
            int y = py + LABEL_ROW_TOP + i * LABEL_ROW_H;
            if ((i & 1) == 0) {
                GuiDraw.drawRect(px + 24, y, px + W_LABEL - 24, y + 16, 0x14FFFFFF);
            }
            this.fontRenderer.drawString(String.valueOf(i + 1), px + 25, y + 4, 0xFF57697A);

            int metric = this.label.metricAt(i);
            String name = LabelLayout.metricName(metric);
            int nameColor = metric == LabelLayout.M_NONE ? 0xFF57697A : 0xFFDCE8F0;
            this.fontRenderer.drawString(clip(name, 96), px + 35, y + 4, nameColor);

            String v = LabelLayout.valueText(d, metric);
            this.fontRenderer.drawString(v,
                    px + W_LABEL - 26 - this.fontRenderer.getStringWidth(v), y + 4, 0xFF7FE3A0);
        }
    }

    /** 密码盘那半页：两行标签 + 一行状态提示 */
    private void drawKeypadSection(int px, int py) {
        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.debug.keypad.note",
                TileKeypad.NOTE_MAX), px + 14, py + 38, 0xFF8FA0B0);
        this.fontRenderer.drawString(I18n.format("gui.jiezhimod.debug.keypad.pass",
                TileKeypad.PASSWORD_LEN), px + 14, py + 72, 0xFF8FA0B0);

        // 状态行：固定模式下把生效的密码写出来（那是玩家要看到的东西）；
        // 随机模式下写当前随机密码，并说明"每次进游戏会自动刷新"。
        String line;
        int color;
        if (this.editMode == TileKeypad.MODE_FIXED) {
            line = I18n.format("gui.jiezhimod.debug.keypad.fixedOn",
                    this.passField == null ? "" : this.passField.getText());
            color = 0xFF7FE3A0;
        } else {
            line = I18n.format("gui.jiezhimod.debug.keypad.randomOn", this.keypad.getPassword());
            color = 0xFFD8B25A;
        }
        this.fontRenderer.drawString(clip(line, W - 28), px + 14, py + 106, color);
    }

    /** {@link #recommend} 返回的第二个数字就是这张表的索引（下标 1 起用，0 留空） */
    private static final String[] RECOMMEND_KEY = {
            "gui.jiezhimod.debug.recommend.custom",
            "gui.jiezhimod.debug.recommend.core",
            "gui.jiezhimod.debug.recommend.component",
            "gui.jiezhimod.debug.recommend.custom",
            "gui.jiezhimod.debug.recommend.graph",
    };

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 全部画在背景层（绝对坐标）
    }

    /**
     * 输入框必须在这里画 —— 它们由 GuiScreen 的绘制流程管理，
     * 不属于「背景层自绘」那一套（背景层画完就被后面的 super.drawScreen 盖过去了）。
     */
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (this.noteField != null) {
            this.noteField.drawTextBox();
        }
        if (this.passField != null) {
            this.passField.drawTextBox();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.noteField != null && this.noteField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (this.passField != null && this.passField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.noteField != null) {
            this.noteField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (this.passField != null) {
            this.passField.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    /**
     * 按设备类型给推荐刷新率。
     *
     * @return {毫秒, {@link #RECOMMEND_KEY} 的索引}，不是本模组的屏返回 null
     */
    private static int[] recommend(Block block) {
        // 注意判定顺序：几个子类都 extends BlockDfcScreen，必须先判子类
        if (block instanceof BlockCustomScreen) {
            return new int[]{200, 3};
        }
        if (block instanceof BlockDfcGraph) {
            return new int[]{200, 4};
        }
        if (block instanceof BlockDfcMonitor) {
            return new int[]{100, 2};
        }
        if (block instanceof BlockDfcScreen) {
            return new int[]{50, 1};
        }
        return null;
    }

    private String clip(String s, int maxWidth) {
        return this.fontRenderer.trimStringToWidth(s, maxWidth);
    }
}
