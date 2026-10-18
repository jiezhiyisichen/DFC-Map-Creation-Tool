package com.jiezhimod.client.util;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * 2D 绘制小工具（实心矩形 / 圆角矩形）。
 *
 * 用 Tessellator 自己拼三角形：1.12.2 的 {@code Gui.drawRect} 只能画方角，
 * 直接按圆弧顶点拼三角形比引入纹理资源更简单，也不怕资源丢失。
 *
 * 【v3.7 修的致命 bug：面板底色一直是透明的】
 * v3.6 及以前，{@code GL_TRIANGLES} 模式下**中心点只发了一次**，后面直接跟了 28 个轮廓点。
 * 顶点是按 3 个一组切成三角形的，于是只有第一个三角形 (中心, p0, p1) 是对的，
 * 其余全成了"轮廓点之间的细缝" —— 整块面板在游戏里表现为**完全透明**。
 * 而按钮和文字都是原版带纹理的绘制路径，看起来"只有它们正常"，很容易误判成 GL 状态问题。
 * 同一个文件里的 {@code drawRect} 更彻底：{@code tri(x0,y0,x1,y1)} 生成的三个顶点里有两个重合，
 * 4 个三角形全是零面积 —— 物品格子从来没画出来过。
 *
 * 现在几何全部挪到 {@link GuiGeometry}（不 import 任何 MC 类），
 * {@code SelfTest} 直接断言"顶点数是 3 的倍数 / 每个三角形面积都不为 0 / 总面积接近 w·h"，
 * 退化三角形再也过不了自测。本类只负责"把顶点灌进 Tessellator"。
 */
@SideOnly(Side.CLIENT)
public final class GuiDraw {

    private GuiDraw() {
    }

    /** 圆角矩形填充；radius 会自动收敛到不超过短边的一半。 */
    public static void drawRoundedRect(float x, float y, float w, float h, float radius, int color) {
        if (w <= 0.0F || h <= 0.0F) {
            return;
        }
        upload(GuiGeometry.roundedRectTriangles(x, y, w, h, radius), color);
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
        upload(GuiGeometry.rectTriangles(x1, y1, x2, y2), color);
    }

    /** 把三角形列表一次性提交（整块图形只 begin/draw 一次，避免丢批次）。 */
    private static void upload(float[] tris, int color) {
        if (tris == null || tris.length < 6) {
            return;
        }
        float a = ((color >>> 24) & 255) / 255.0F;
        float rr = ((color >> 16) & 255) / 255.0F;
        float gg = ((color >> 8) & 255) / 255.0F;
        float bb = (color & 255) / 255.0F;

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);

        buf.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i + 1 < tris.length; i += 2) {
            buf.pos(tris[i], tris[i + 1], 0.0D).color(rr, gg, bb, a).endVertex();
        }
        tess.draw();

        GlStateManager.enableTexture2D();
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
