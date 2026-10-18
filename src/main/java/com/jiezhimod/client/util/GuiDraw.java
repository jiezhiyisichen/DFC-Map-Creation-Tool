package com.jiezhimod.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * 2D 绘制小工具（实心矩形 / 圆角矩形）。
 *
 * ==================================================================
 * 【v3.8：改成"和 drawString 完全一样"的绘制方式】
 *
 * 之前用 {@code disableTexture2D() + POSITION_COLOR} 画纯色三角形，在游戏里**完全不显示** ——
 * 而同一帧、同一个方法里 {@code fontRenderer.drawString} 画的字却在。
 * 两者的唯一差别就是"带不带纹理"。这个整合包里 HBM 会直接改 GL 状态，
 * {@code GlStateManager} 的纹理开关缓存很可能已经失同步：这时 {@code disableTexture2D()}
 * 成了空操作、纹理仍然开着，而 POSITION_COLOR 的顶点没有 UV，采样结果不确定，
 * 表现就是"画了但看不见"。
 *
 * 所以现在**不关纹理**：绑定自带的纯白 8×8 纹理（{@code textures/gui/white.png}），
 * 用 {@code POSITION_TEX_COLOR} 提交、所有顶点 UV 都取白色像素中心。
 * 这条路径与 {@code FontRenderer} 完全一致 —— 字能显示，填充就一定能显示。
 * 而且换了纹理之后，无论 GlStateManager 的纹理缓存是对是错都成立：
 *   · 纹理本来就被打开 → 我们绑白纹理 → 正确；
 *   · 纹理是正常关闭的 → enableTexture2D + bind → 正确。
 *
 * 几何仍在 {@link GuiGeometry}（不 import 任何 MC 类），{@code SelfTest} 有断言。
 * ==================================================================
 */
@SideOnly(Side.CLIENT)
public final class GuiDraw {

    /** 纯白 8×8 纹理；所有填充都靠它 + 逐顶点颜色上色 */
    public static final ResourceLocation WHITE =
            new ResourceLocation("jiezhimod", "textures/gui/white.png");

    /** 白色像素正中间的 UV（8×8，中心 = 4/8） */
    private static final float U = 0.5F;
    private static final float V = 0.5F;

    /** 排错用：只在第一次真正提交顶点时打一行日志，不刷屏 */
    private static boolean loggedOnce = false;

    private GuiDraw() {
    }

    /** 圆角矩形填充；radius 会自动收敛到不超过短边的一半。 */
    public static void drawRoundedRect(float x, float y, float w, float h, float radius, int color) {
        if (w <= 0.0F || h <= 0.0F) {
            return;
        }
        upload(GuiGeometry.roundedRectTriangles(x, y, w, h, radius), color, "rounded");
    }

    /**
     * 实心矩形填充（ARGB）。
     *
     * 与 drawRoundedRect 分开：热量条这种"必须精确对齐 1px 槽位"的 UI
     * 不该走三角扇（圆弧顶点会让 1px 线看起来发虚），直接画两个三角形最稳。
     */
    public static void drawRect(int x1, int y1, int x2, int y2, int color) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        upload(GuiGeometry.rectTriangles(x1, y1, x2, y2), color, "rect");
    }

    /** 把三角形列表一次性提交（整块图形只 begin/draw 一次，避免丢批次）。 */
    private static void upload(float[] tris, int color, String kind) {
        if (tris == null || tris.length < 6) {
            return;
        }
        float a = ((color >>> 24) & 255) / 255.0F;
        float rr = ((color >> 16) & 255) / 255.0F;
        float gg = ((color >> 8) & 255) / 255.0F;
        float bb = (color & 255) / 255.0F;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getTextureManager() == null) {
            return;
        }

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GlStateManager.enableBlend();
        // 关键：**不关纹理**，改成绑一张白纹理（见类注释）
        GlStateManager.enableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        mc.getTextureManager().bindTexture(WHITE);

        buf.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (int i = 0; i + 1 < tris.length; i += 2) {
            buf.pos(tris[i], tris[i + 1], 0.0D).tex(U, V).color(rr, gg, bb, a).endVertex();
        }
        tess.draw();

        if (!loggedOnce) {
            loggedOnce = true;
            int texId = -1;
            try {
                net.minecraft.client.renderer.texture.ITextureObject t =
                        mc.getTextureManager().getTexture(WHITE);
                if (t != null) {
                    texId = t.getGlTextureId();
                }
            } catch (Throwable ignored) {
                // 纹理没加载出来时这里可能抛；不打断绘制，让日志说明问题
            }
            com.jiezhimod.JiezhiMod.warn("[GuiDraw] first fill: kind={} verts={} color=#{} "
                            + "tex={} texId={} (texId<=0 表示白纹理没加载出来)",
                    kind, tris.length / 2, Integer.toHexString(color).toUpperCase(), WHITE, texId);
        }
    }

    /** 把 ARGB 整数按比例套一层不透明度，返回同形式的值 */
    public static int withAlpha(int color, float alpha) {
        if (alpha <= 0.0F) {
            return color & 0x00FFFFFF;
        }
        int base = (color >>> 24) & 255;
        int a = Math.max(0, Math.min(255, Math.round(base * Math.min(1.0F, alpha))));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
