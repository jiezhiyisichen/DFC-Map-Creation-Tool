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
 * 密码盘的**顶面面板**渲染器。
 *
 * ==================================================================
 * 【v3.9.2 的两个改动，都是实机反馈逼出来的】
 *
 * 1) **底座不再由 TESR 画**。v3.9 里方块是 {@code INVISIBLE}、整个底座用
 *    {@code disableTexture2D + POSITION_COLOR} 自绘 —— 实机结果是**方块完全不可见**，
 *    只看得见一个半格高的碰撞框（玩家截图里就是个空方框）。
 *    现在底座交给**普通方块模型**（{@code blockstates/keypad.json} → {@code models/block/keypad.json}，
 *    0~8 像素高的立方体），TESR 只画上面那块动态面板。
 *    这样即使面板出问题，玩家至少看到一块金属方台，而不是"空气方块"。
 *
 * 2) **UV 与命中判定同源**：面板 quad 的 UV 与 {@link KeypadLayout#hitWorld}
 *    完全对应（v=0 在 -Z 一侧、u=0 在 -X 一侧），
 *    玩家看到的"左上角"就是画布的 (0,0)。
 * ==================================================================
 */
public class RenderKeypad extends TileEntitySpecialRenderer<TileKeypad> {

    /**
     * 面板离模型顶面的高度。模型顶面在 y=0.5，这里再抬 2mm ——
     * 太近会 z-fighting（画面出条纹），太远会看出"面板浮在半空"。
     */
    private static final float PANEL_Y = 0.502F;

    /** 排错用：每台密码盘只打一次首帧日志 */
    private static final java.util.Set<String> LOGGED = new java.util.HashSet<String>();

    @Override
    public void render(TileKeypad te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        // 整段 try/catch：TESR 里抛异常时 1.12.2 会吞掉堆栈、这一帧什么都不画，
        // 表现就是"方块活着但看不见"。至少把原因写进日志。
        try {
            ScreenCanvas canvas = te.canvas();
            long now = System.currentTimeMillis();
            canvas.renderKeypad(te.getPassword(), te.getBuffer(), TileKeypad.PASSWORD_LEN,
                    te.isLocked(), te.isAccepted(), te.isRejecting(), now);
            canvas.uploadIfDirty(now);

            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, z);
            if (canvas.texture() == null) {
                // 兜底：纹理没建出来时画一块深色顶面，至少让玩家看到"这里有块面板"。
                // TESR 里 POSITION_COLOR + GL_QUADS 是能显示的（DFC 外壳就是这条路）。
                logOnce(te, "canvas.texture() == null，改用纯色顶面兜底");
                drawFallbackPanel();
            } else {
                drawPanel(canvas);
            }
            GlStateManager.popMatrix();

            logOnce(te, "first frame ok, texId=" + canvas.texture().getGlTextureId());
        } catch (Throwable t) {
            logOnce(te, "RENDER FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void logOnce(TileKeypad te, String msg) {
        String key = String.valueOf(te.getPos());
        if (LOGGED.contains(key)) {
            return;
        }
        LOGGED.add(key);
        com.jiezhimod.JiezhiMod.warn("[KeypadRender] " + key + " " + msg);
    }

    /** 兜底顶面：没有纹理时画一块深色板，保证"看得见" */
    private void drawFallbackPanel() {
        float p0 = KeypadLayout.PANEL_MIN;
        float p1 = KeypadLayout.PANEL_MAX;
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        float r = 0.10F;
        float g = 0.12F;
        float b = 0.16F;
        buf.pos(p0, PANEL_Y, p0).color(r, g, b, 1.0F).endVertex();
        buf.pos(p1, PANEL_Y, p0).color(r, g, b, 1.0F).endVertex();
        buf.pos(p1, PANEL_Y, p1).color(r, g, b, 1.0F).endVertex();
        buf.pos(p0, PANEL_Y, p1).color(r, g, b, 1.0F).endVertex();
        tess.draw();
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
    }

    // ==================== 面板 ====================

    private void drawPanel(ScreenCanvas canvas) {
        float p0 = KeypadLayout.PANEL_MIN;
        float p1 = KeypadLayout.PANEL_MAX;

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        // 必须先回到 0 号纹理单元：别的 mod 可能把活动单元切走，
        // 那时这里会把纹理绑到错误单元，quad 采到的还是旧图集
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(canvas.texture().getGlTextureId());

        // GL_QUADS：与 DFC 屏幕面板同一条路径（这条路径确认能显示）
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);

        // 顶面朝 +Y，顶点顺序照抄 ModelBox 的 +Y 面（从外面看逆时针）
        buf.pos(p0, PANEL_Y, p0).tex(0.0F, 0.0F).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        buf.pos(p1, PANEL_Y, p0).tex(1.0F, 0.0F).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        buf.pos(p1, PANEL_Y, p1).tex(1.0F, 1.0F).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        buf.pos(p0, PANEL_Y, p1).tex(0.0F, 1.0F).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();

        tess.draw();

        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
    }
}
