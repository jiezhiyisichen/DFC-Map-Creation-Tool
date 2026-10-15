package com.jiezhimod.client.util;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * 2D 绘制小工具（圆角矩形）。
 *
 * 用 Tessellator 自己拼扇形 + 矩形：1.12.2 的 Gui.drawRect 只能画方角，
 * 直接贴 ≥8 段扇形做圆角比引入额外纹理更简单也不怕资源缺失。
 */
@SideOnly(Side.CLIENT)
public final class GuiDraw {

    private GuiDraw() {
    }

    /**
     * 圆角矩形填充；radius 会自动收敛到不超过短边的一半。
     *
     * 【为什么必须"一次 begin、一次 draw"】
     * 之前的实现先 buf.begin(QUADS) 画三条直边并 tess.draw()，再 buf.begin(TRIANGLES)
     * 画四个圆角并二次 tess.draw()。1.12.2 的 Tessellator 是**单例**，
     * 第二次 draw() 在部分渲染路径下（尤其外层已开启 GL_TRIANGLES 批处理时）
     * 不会真正把缓冲提交到屏幕，结果就是四条直边出来了、四个角没了 ——
     * 表现为"圆角变成了被挖掉一角的方块"，四角是透明的。
     *
     * 现在整块图形（直边 + 四角）统一塞进**同一个** BufferBuilder，
     * 以 GL_TRIANGLES 一次画完、只 draw 一次，从根上避免"分两次提交丢批次"。
     * 圆角矩形是凸多边形，用中心点向所有轮廓点做三角扇（GL_TRIANGLES）即可完整覆盖。
     */
    public static void drawRoundedRect(float x, float y, float w, float h, float radius, int color) {
        if (w <= 0.0F || h <= 0.0F) {
            return;
        }
        float r = Math.max(0.0F, Math.min(radius, Math.min(w, h) / 2.0F));
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

        // 单次提交：整个圆角矩形一次画完
        buf.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        if (r <= 0.0F) {
            // 退化成方角时也走三角扇，保证只有一种绘制路径
            tri(buf, x, y, x + w, y, rr, gg, bb, a);
            tri(buf, x + w, y, x + w, y + h, rr, gg, bb, a);
            tri(buf, x + w, y + h, x, y + h, rr, gg, bb, a);
            tri(buf, x, y + h, x, y, rr, gg, bb, a);
        } else {
            // 圆角矩形是凸图形：中心点 + 轮廓顶点做三角扇，必然无缺口、无重叠
            float cx = x + w / 2.0F;
            float cy = y + h / 2.0F;
            int[] segs = arcSegments(r);
            int n = outlinePointCount(r, segs);

            // 中心点先放一次（扇形共用顶点）
            buf.pos(cx, cy, 0.0D).color(rr, gg, bb, a).endVertex();
            for (int i = 0; i < n; i++) {
                float[] p = outlinePoint(x, y, w, h, r, segs, i, n);
                buf.pos(p[0], p[1], 0.0D).color(rr, gg, bb, a).endVertex();
            }
        }
        tess.draw();

        GlStateManager.enableTexture2D();
    }

    /**
     * 实心矩形填充（ARGB）。
     *
     * 与 drawRoundedRect 分开：热量条这种"必须精确对齐 1px 槽位"的 UI
     * 不该走三角扇（抗锯齿边缘会让 1px 线看起来发虚），直接画方块最稳。
     */
    public static void drawRect(int x1, int y1, int x2, int y2, int color) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        float a = ((color >>> 24) & 255) / 255.0F;
        float r = ((color >> 16) & 255) / 255.0F;
        float g = ((color >> 8) & 255) / 255.0F;
        float b = (color & 255) / 255.0F;

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        buf.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        tri(buf, x1, y1, x2, y1, r, g, b, a);
        tri(buf, x2, y1, x2, y2, r, g, b, a);
        tri(buf, x1, y1, x2, y2, r, g, b, a);
        tri(buf, x1, y1, x1, y2, r, g, b, a);
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

    /** 一个三角形（逆时针：左上 -> 右上 -> 右下） */
    private static void tri(BufferBuilder buf, float x0, float y0, float x1, float y1,
                            float r, float g, float b, float a) {
        buf.pos(x0, y0, 0.0D).color(r, g, b, a).endVertex();
        buf.pos(x1, y0, 0.0D).color(r, g, b, a).endVertex();
        buf.pos(x1, y1, 0.0D).color(r, g, b, a).endVertex();
    }

    /**
     * 每个圆角的分段数。半径越小分段越少，避免小尺寸控件白白堆顶点。
     */
    private static int[] arcSegments(float r) {
        int seg = (int) Math.ceil(r / 1.5D);
        if (seg < 3) {
            seg = 3;
        }
        if (seg > 12) {
            seg = 12;
        }
        return new int[]{seg, seg, seg, seg};
    }

    /** 轮廓点总数：四角各 seg+1 个端点（含四个直边与圆角的衔接点） */
    private static int outlinePointCount(float r, int[] segs) {
        int n = 0;
        for (int s : segs) {
            n += s + 1;
        }
        return n;
    }

    /**
     * 取轮廓上的第 i 个点（顺时针，屏幕坐标 y 向下）。
     *
     * 四段圆弧各自从 180° 起逆时针扫到 270°，段与段之间靠"上一个终点 = 下一个起点"
     * 衔接，直边（上下左右四条）由扇形自动跨接，因此无需额外插入直边顶点。
     */
    private static float[] outlinePoint(float x, float y, float w, float h, float r,
                                        int[] segs, int i, int total) {
        // 定位 i 落在第几个圆角
        int corner = 0;
        int acc = 0;
        while (corner < 3 && i >= acc + segs[corner] + 1) {
            acc += segs[corner] + 1;
            corner++;
        }
        int step = i - acc;
        int seg = segs[corner];
        double t = (double) step / (double) seg;

        // 0=左上 1=右上 2=右下 3=左下；起始角与圆心按顺时针依次排布
        double startDeg;
        float ccx;
        float ccy;
        switch (corner) {
            case 0:
                startDeg = 180.0D;
                ccx = x + r;
                ccy = y + r;
                break;
            case 1:
                startDeg = 270.0D;
                ccx = x + w - r;
                ccy = y + r;
                break;
            case 2:
                startDeg = 0.0D;
                ccx = x + w - r;
                ccy = y + h - r;
                break;
            default:
                startDeg = 90.0D;
                ccx = x + r;
                ccy = y + h - r;
                break;
        }
        // 屏幕坐标 y 向下，故直接用 sin 得到"顺时针"观感
        double ang = (startDeg + 90.0D * t) * Math.PI / 180.0D;
        return new float[]{
                (float) (ccx + Math.cos(ang) * r),
                (float) (ccy + Math.sin(ang) * r)
        };
    }
}
