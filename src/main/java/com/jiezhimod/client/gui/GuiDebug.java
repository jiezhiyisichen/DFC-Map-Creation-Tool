package com.jiezhimod.client.gui;

import com.jiezhimod.block.BlockCustomScreen;
import com.jiezhimod.block.BlockDfcGraph;
import com.jiezhimod.block.BlockDfcMonitor;
import com.jiezhimod.block.BlockDfcScreen;
import com.jiezhimod.block.ContainerDebug;
import com.jiezhimod.block.TileDfcScreen;
import com.jiezhimod.client.util.GuiDraw;
import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageDebugConfig;
import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;

/**
 * 调试界面（调试棒蹲下右键本模组方块打开）。
 *
 * 现在只有显示器有可调项：**屏幕刷新率**。
 * 默认 50ms（20Hz），但每块屏每帧都要重画 768×576 画布再整张上传纹理 ——
 * 四块一起开时开销很可观（玩家实测 9 FPS）。部件页、自定义屏的读数变化本来就慢，
 * 调慢到 100 / 200ms 肉眼几乎看不出差别，开销直接降到 1/2 ~ 1/4。
 *
 * 【推荐值】**按设备类型给建议**，而不是一刀切：
 *   · 核心屏（暗核聚变堆核心）—— 50ms，温度/稳定度跳得快；
 *   · 部件监视器 —— 100ms，发射器/储罐读数慢；
 *   · 自定义显示屏 —— 200ms，只是几个设备读数，没人盯着看小数第三位；
 *   · 曲线显示器 —— 200ms，曲线本身 500ms 才采一个点，重绘再快也画不出新形态。
 *
 * 【状态来源】当前值读方块实体的客户端镜像（服务端广播回来的）；
 * 点按钮只发包，不本地改 —— 服务端拒绝时界面不会显示假选中。
 */
public class GuiDebug extends GuiContainer {

    private static final int W = 248;
    private static final int H = 150;
    private static final int BTN_W = 68;
    private static final int BTN_H = 16;
    private static final int ID_CHOICE_BASE = 0;
    private static final int ID_CLOSE = 100;

    private final BlockPos pos;
    private final TileDfcScreen screen;   // 可能为 null（不是显示器）

    public GuiDebug(BlockPos pos, ContainerDebug container) {
        super(container);
        this.pos = pos;
        this.xSize = W;
        this.ySize = H;

        TileEntity te = null;
        if (net.minecraft.client.Minecraft.getMinecraft().world != null) {
            te = net.minecraft.client.Minecraft.getMinecraft().world.getTileEntity(pos);
        }
        this.screen = te instanceof TileDfcScreen ? (TileDfcScreen) te : null;
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
        refreshButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == ID_CLOSE) {
            this.mc.displayGuiScreen(null);
            return;
        }
        if (this.screen != null) {
            int[] choices = TileDfcScreen.REFRESH_CHOICES_MS;
            int idx = button.id - ID_CHOICE_BASE;
            if (idx >= 0 && idx < choices.length) {
                ModNetwork.CHANNEL.sendToServer(new MessageDebugConfig(this.pos, choices[idx]));
            }
        }
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
