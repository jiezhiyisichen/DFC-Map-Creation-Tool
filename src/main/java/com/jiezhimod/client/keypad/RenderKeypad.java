package com.jiezhimod.client.keypad;

import com.jiezhimod.block.TileKeypad;
import com.jiezhimod.client.dfc.ScreenCanvas;
import com.jiezhimod.keypad.KeypadLayout;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * 密码盘的渲染器：**底座 + 顶面面板**全部自己画。
 *
 * 方块本身的 {@code getRenderType()} 是 INVISIBLE（模型画不了动态纹理），
 * 所以这里必须把整个方块画出来 —— 漏一块就会"从某个角度能看穿"。
 *
 * 面板纹理直接复用 DFC 显示器那套 {@link ScreenCanvas}：
 * 同一张画布、同一套"自管 direct buffer 直传"的上传路径（那条路在本整合包里
 * 是验证过能显示的唯一一条，见 ScreenCanvas.uploadIfDirty 的说明）。
 */
public class RenderKeypad extends TileEntitySpecialRenderer<TileKeypad> {

    /** 底座颜色：深灰金属，和红色台面拉开对比 */
    private static final int C_BASE = 0xFF1A1D22;
    private static final int C_BASE_TOP = 0xFF22262C;
    private static final int C_EDGE = 0xFF2B323A;

    /** 面板离顶面高度：留 2mm 的缝，避免和底座顶面共面闪烁 */
    private static final float PANEL_Y = 0.502F;

    @Override
    public void render(TileKeypad te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        ScreenCanvas canvas = te.canvas();
        long now = System.currentTimeMillis();
        canvas.renderKeypad(te.getPassword(), te.getBuffer(), TileKeypad.PASSWORD_LEN,
                te.isLocked(), te.isAccepted(), te.isRejecting(), now);
        canvas.uploadIfDirty(now);

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.disableLighting();
        // 顶面只有一个朝上的面片，开剔除时从侧面斜看会被剔掉
        GlStateManager.disableCull();

        drawBase();
        drawPanel(canvas);

        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    // ==================== 底座 ====================

    private void drawBase() {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        GlStateManager.disableTexture2D();
        GlStateManager.disableBlend();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        // 半格高（0~0.5）的金属块
        box(buf, 0.0F, 0.0F, 0.0F, 1.0F, 0.5F, 1.0F, C_BASE);
        // 顶面略微亮一点，做出"面板嵌在一块厚板里"的层次
        box(buf, 0.0F, 0.5F, 0.0F, 1.0F, 0.5F, 1.0F, C_BASE_TOP);

        tess.draw();
        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();
    }

    // ==================== 面板 ====================

    private void drawPanel(ScreenCanvas canvas) {
        if (canvas.texture() == null) {
            return;
        }
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        float p0 = KeypadLayout.PANEL_MIN;
        float p1 = KeypadLayout.PANEL_MAX;

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        // 必须先回到 0 号纹理单元：别的 mod 可能把活动单元切走，
        // 切成别的时候这里会把纹理绑到错误单元上，quad 采样到的仍是旧图集
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(canvas.texture().getGlTextureId());

        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);

        // 顶面朝 +Y。顶点顺序照抄 ModelBox 的 +Y 面（从外面看逆时针）。
        // UV 与 KeypadLayout.hitWorld 完全对应：v=0 在 -Z 一侧、u=0 在 -X 一侧，
        // 所以玩家看到的"左上角"就是画布的 (0,0)。
        buf.pos(p0, PANEL_Y, p0).tex(0.0F, 0.0F).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        buf.pos(p1, PANEL_Y, p0).tex(1.0F, 0.0F).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        buf.pos(p1, PANEL_Y, p1).tex(1.0F, 1.0F).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        buf.pos(p0, PANEL_Y, p1).tex(0.0F, 1.0F).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();

        tess.draw();

        GlStateManager.disableBlend();
    }

    // ==================== 几何原语 ====================

    /**
     * 画一个长方体。顶点绕向照抄原版 ModelBox —— 写反了会被背面剔除吃掉，
     * 症状是"从某个角度能看穿方块"。
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
