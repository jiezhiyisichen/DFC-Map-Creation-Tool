package com.jiezhimod.client.dfc;

import com.jiezhimod.block.BlockDfcMonitor;
import com.jiezhimod.block.BlockDfcScreen;
import com.jiezhimod.block.TileDfcScreen;
import com.jiezhimod.dfc.ScreenLayout;
import com.jiezhimod.dfc.ScreenStructure;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * 显示器渲染器：整块设备（吊架 + 外壳 + 屏幕）全部在这里画。
 *
 * 【为什么不用 JSON 方块模型】
 * 面板要按玩家放置时的视线下倾 —— 这个自由度静态模型表达不了
 * （要么给每个角度写一套模型，要么放弃倾斜）。交给 TESR 之后倾斜只是矩阵里的
 * 一个 rotate，外壳的倒角、吊架的收腰这些"现代感"细节也能直接用代码画出来。
 *
 * 【本地坐标系：原点 = 面板几何中心，这一点是强制的】
 *   x: -2  -> 2   （右）
 *   y: -1.5 -> 1.5 （上）
 *   z: 0 是面板中心平面，**-z 是正面**（朝向 facing）
 * 早期版本把原点放在 part 0 的方块角上（x ∈ [0, 4] 单侧），
 * 结果绕 Y 轴转 90/180/270 度时整段区间被甩到坐标轴负半轴 ——
 * NORTH 以外的三个朝向，画出来的屏幕与真实的 12 个方块**整整错开一格**
 * （自测里 12/12 格全部 mismatch）。改成中心对称后，
 * x 与 y 都跨越原点两侧，任何旋转都落回原处。
 *
 * 【吊装】{@link ScreenStructure} 里 part 0 是结构的**左上角**，
 * 玩家对天花板下表面放置即为吊装。这里屏幕绕**顶边**（y = +1.5）下倾：
 * 顶边贴着天花板不动，下缘朝玩家甩出来 —— 站在下面正好抬头看到整块屏。
 * 吊架刻意画在倾斜**之外**：它的全部几何都位于 y > 1.5，
 * 而 y = 1.5 正是旋转轴，所以无论倾角多大都不会和屏幕穿模。
 */
@SideOnly(Side.CLIENT)
public class RenderDfcScreen extends TileEntitySpecialRenderer<TileDfcScreen> {

    // ==================== 配色（冷灰金属 + 深色内衬） ====================
    private static final int C_BACK = 0xFF3B4148;      // 背板
    private static final int C_FRAME = 0xFF565E67;     // 前框
    private static final int C_FRAME_DARK = 0xFF2A2F35; // 前框内沿（做出厚度感）
    private static final int C_ARM = 0xFF31373D;       // 吊臂
    private static final int C_MOUNT = 0xFF454C53;     // 顶部法兰 / 转轴座
    private static final int C_HINGE = 0xFF5B636C;     // 转轴亮边

    private static final float HW = ScreenStructure.COLS / 2.0F;   // 半宽 2 格
    private static final float HH = ScreenStructure.ROWS / 2.0F;   // 半高 1.5 格
    private static final float T = 0.075F;                // 半厚
    private static final float BEZEL = 0.10F;             // 边框宽度
    private static final float GAP = 0.012F;              // 四周留白，避免与相邻方块面重面闪烁

    @Override
    public void render(TileDfcScreen te, double x, double y, double z,
                       float partialTicks, int destroyStage, float alpha) {
        if (te == null || te.getWorld() == null) {
            return;
        }
        IBlockState state = te.getWorld().getBlockState(te.getPos());
        if (!(state.getBlock() instanceof BlockDfcScreen)) {
            return;
        }
        // 只有控制器会走到这里（11 个占位块是另一个方块类型，没有方块实体，
        // 也不会被挂上 TESR），所以整个结构天然只渲染一次。

        ScreenCanvas canvas = te.canvas();
        boolean powered = te.isPowered();
        // 同一台"外壳 + 屏幕"承载四块显示器：控制器方块决定显示哪一页。
        // 这样后几块屏不需要另写 TESR / 会话，只有数据页不同。
        // 判定走 ScreenLayout.pageFor（纯函数，离线自测里钉住"哪种方块 -> 哪一页"）。
        Block ctrl = state.getBlock();
        int page = ScreenLayout.pageFor(
                ctrl instanceof com.jiezhimod.block.BlockCustomScreen,
                ctrl instanceof BlockDfcMonitor,
                ctrl instanceof com.jiezhimod.block.BlockDfcGraph);
        // 【熔毁报错页】核心开始炸（坍缩度离开 0）时整屏改画 301，4 秒后自动重启。
        // 优先级最高：报错页连开机自检都盖掉 —— 屏幕"已经坏了"，还在画进度条就不像了。
        // 只有开着的屏才推进这段状态机（关着的屏本来就什么都不显示）。
        // 把"这台屏自己的刷新率"交给画布：调试棒里调过之后立刻生效
        canvas.setMainStepMs(te.getRefreshMs());
        canvas.render(powered ? te.clientBootElapsedMs() : -1L, powered,
                te.getData(), te.getEvents(), page,
                powered ? te.clientFaultElapsedMs() : -1L);
        canvas.uploadIfDirty(System.currentTimeMillis());

        EnumFacing facing = state.getValue(BlockDfcScreen.FACING);
        float tilt = ScreenStructure.tiltDegrees(te.getTilt());

        // 把原点从"part 0 的方块角"搬到整个面板的几何中心 ——
        // 偏移量由 ScreenStructure 统一给出，和 partPos 用的是同一套坐标约定。
        double[] anchor = ScreenStructure.anchor(facing);

        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();

        GlStateManager.translate(x + anchor[0], y + anchor[1], z + anchor[2]);
        GlStateManager.rotate(ScreenStructure.yawOf(facing), 0.0F, 1.0F, 0.0F);

        // 前推量随倾角自适应。这样等价于"屏幕绕自己的几何中心倾斜"：
        // 0 度时屏幕正好落在占位平面上，30 度时上下缘各让开 0.75 格，怎么转都不会
        // 整块插进背后的方块里。顶边的位移也被下面的吊架宽度吸收，看不出接缝。
        float push = HH * (float) Math.sin(Math.toRadians(tilt));
        GlStateManager.translate(0.0F, 0.0F, -push);

        // 吊架固定不动：它整个位于转轴之上，始终稳稳连着天花板
        drawHanger();

        // 屏幕绕**顶边**下倾。负号是关键 —— 见 ScreenStructure.rotateX 的说明：
        // rotate(+tilt) 会让屏幕仰望天花板，站在下面的玩家只能看到背壳。
        GlStateManager.translate(0.0F, HH, 0.0F);
        GlStateManager.rotate(-tilt, 1.0F, 0.0F, 0.0F);
        GlStateManager.translate(0.0F, -HH, 0.0F);

        drawShell();
        drawPanel(canvas);

        GlStateManager.popAttrib();
        GlStateManager.popMatrix();
    }

    // ==================== 吊架 ====================

    /**
     * 天花板吊装支架。全部几何位于 y > HH（旋转轴）之上，
     * 所以倾角怎么变都不会插进屏幕里。
     *
     * 自上而下：法兰盘（贴天花板）-> 两根吊臂 -> 转轴座（横向圆管）
     * 再加两颗外露的铰链台，做出"能调角度"的机械感。
     */
    private void drawHanger() {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();

        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        // 注意这里已经在前推变换之后：屏幕面就落在 z = 0 上。
        // 另外本地 y = +HH 正好是 part 0 格子的上边缘（= 天花板下表面），
        // 所以 y 越过 HH 的部分就是"埋进天花板"，正是吊装该有的观感。
        // 整个吊架都位于转轴之上，永远不动；屏幕绕 y = HH 那条轴线摆动，两者严丝合缝。

        // 夹钳状的铰链座：横贯屏幕顶端，把它整个"咬"住
        box(buf, -1.35F, HH - 0.08F, -0.22F, 1.35F, HH + 0.10F, 0.30F, C_HINGE);
        // 两端各一个铰链台，做出"能调角度"的机械感
        box(buf, -1.48F, HH - 0.06F, -0.16F, -1.28F, HH + 0.20F, 0.22F, C_MOUNT);
        box(buf, 1.28F, HH - 0.06F, -0.16F, 1.48F, HH + 0.20F, 0.22F, C_MOUNT);

        // 两根短吊柱，向上插进天花板
        box(buf, -1.00F, HH + 0.08F, -0.09F, -0.88F, HH + 0.46F, 0.09F, C_ARM);
        box(buf, 0.88F, HH + 0.08F, -0.09F, 1.00F, HH + 0.46F, 0.09F, C_ARM);

        // 天花板法兰：宽扁的一块，视觉上"吃"进天花板
        box(buf, -1.50F, HH + 0.36F, -0.28F, 1.50F, HH + 0.46F, 0.28F, C_MOUNT);
        // 法兰下方两颗外露的螺栓头
        box(buf, -1.30F, HH + 0.28F, -0.09F, -1.18F, HH + 0.36F, 0.09F, C_HINGE);
        box(buf, 1.18F, HH + 0.28F, -0.09F, 1.30F, HH + 0.36F, 0.09F, C_HINGE);

        tess.draw();
    }

    // ==================== 外壳 ====================

    private void drawShell() {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();

        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        float e = GAP;
        float x0 = -HW + e;
        float x1 = HW - e;
        float y0 = -HH + e;
        float y1 = HH - e;

        // 背板：比正面略小一圈，做出"面板嵌在壳里"的层次
        box(buf, x0 + 0.02F, y0 + 0.02F, T, x1 - 0.02F, y1 - 0.02F, T + 0.05F, C_BACK);

        // 前框四条边（沿用的是"正面比背板更亮/更深"的层次做法）
        float zf = -T - 0.010F;   // 前框最前面
        float zb = T;

        box(buf, x0, y0, zf, x1, y0 + BEZEL, zb, C_FRAME);            // 下
        box(buf, x0, y1 - BEZEL, zf, x1, y1, zb, C_FRAME);            // 上
        box(buf, x0, y0 + BEZEL, zf, x0 + BEZEL, y1 - BEZEL, zb, C_FRAME);  // 左
        box(buf, x1 - BEZEL, y0 + BEZEL, zf, x1, y1 - BEZEL, zb, C_FRAME);  // 右
        // 面板与框之间的阴影缝
        box(buf, x0 + BEZEL - 0.014F, y0 + BEZEL - 0.014F, -T + 0.004F,
                x1 - BEZEL + 0.014F, y1 - BEZEL + 0.014F, -T + 0.011F, C_FRAME_DARK);

        tess.draw();
    }

    // ==================== 屏幕 ====================

    private void drawPanel(ScreenCanvas canvas) {
        if (canvas.texture() == null) {
            return;
        }
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        float x0 = -HW + GAP + BEZEL;
        float x1 = HW - GAP - BEZEL;
        float y0 = -HH + GAP + BEZEL;
        float y1 = HH - GAP - BEZEL;
        float zf = -T - 0.003F;   // 略在边框之内，避免与边框共面闪烁

        GlStateManager.enableTexture2D();
        // 屏幕是自发光的：关掉光照，让面板在暗处也清清楚楚
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        // 【必须先回到 0 号纹理单元】见 ScreenCanvas.uploadIfDirty 里的说明：
        // 活动单元被其他 mod 切走时，这里会把纹理绑到错误单元，
        // quad 实际采样的仍是 0 号单元上的旧纹理（通常是地形图集）。
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(canvas.texture().getGlTextureId());

        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);

        // -Z 面朝外（正面）。顶点顺序照抄 ModelBox 的 NORTH 面，
        // 保证背面剔除不会把屏幕本身剔掉。
        //
        // UV 一律从 ScreenStructure.panelUv() 取，不要在这里写死 ——
        // u 的方向是反的（本地 +x 落在观察者左手边），写死很容易又改成镜像的。
        // 详见该方法上的说明。
        float[] uv = ScreenStructure.panelUv();
        float ux0 = uv[0];
        float ux1 = uv[1];
        float vy0 = uv[2];
        float vy1 = uv[3];
        buf.pos(x1, y0, zf).tex(ux1, vy0).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        buf.pos(x0, y0, zf).tex(ux0, vy0).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        buf.pos(x0, y1, zf).tex(ux0, vy1).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        buf.pos(x1, y1, zf).tex(ux1, vy1).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();

        tess.draw();

        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
    }

    // ==================== 几何原语 ====================

    /**
     * 画一个长方体（6 个面）。
     * 顶点顺序与顶点绕向照抄原版 ModelBox：从外面看是逆时针，
     * 顺序写反的话这个面会被背面剔除吃掉 —— 症状是"从某个角度能看穿方块"。
     */
    private static void box(BufferBuilder buf, float x0, float y0, float z0,
                            float x1, float y1, float z1, int argb) {
        float a = ((argb >>> 24) & 255) / 255.0F;
        float r = ((argb >> 16) & 255) / 255.0F;
        float g = ((argb >> 8) & 255) / 255.0F;
        float b = (argb & 255) / 255.0F;

        // -Z
        v(buf, x1, y0, z0, r, g, b, a); v(buf, x0, y0, z0, r, g, b, a);
        v(buf, x0, y1, z0, r, g, b, a); v(buf, x1, y1, z0, r, g, b, a);
        // +Z
        v(buf, x0, y0, z1, r, g, b, a); v(buf, x1, y0, z1, r, g, b, a);
        v(buf, x1, y1, z1, r, g, b, a); v(buf, x0, y1, z1, r, g, b, a);
        // -X
        v(buf, x0, y0, z0, r, g, b, a); v(buf, x0, y0, z1, r, g, b, a);
        v(buf, x0, y1, z1, r, g, b, a); v(buf, x0, y1, z0, r, g, b, a);
        // +X
        v(buf, x1, y0, z1, r, g, b, a); v(buf, x1, y0, z0, r, g, b, a);
        v(buf, x1, y1, z0, r, g, b, a); v(buf, x1, y1, z1, r, g, b, a);
        // -Y
        v(buf, x0, y0, z1, r, g, b, a); v(buf, x1, y0, z1, r, g, b, a);
        v(buf, x1, y0, z0, r, g, b, a); v(buf, x0, y0, z0, r, g, b, a);
        // +Y
        v(buf, x0, y1, z0, r, g, b, a); v(buf, x1, y1, z0, r, g, b, a);
        v(buf, x1, y1, z1, r, g, b, a); v(buf, x0, y1, z1, r, g, b, a);
    }

    private static void v(BufferBuilder buf, float x, float y, float z,
                          float r, float g, float b, float a) {
        buf.pos(x, y, z).color(r, g, b, a).endVertex();
    }
}
