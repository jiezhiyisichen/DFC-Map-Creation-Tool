package com.jiezhimod.client.util;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * 2D 绘制小工具（实心矩形 / 圆角矩形 / 半透明底纹）。
 *
 * ==================================================================
 * 【v3.9.2：改用 GL_QUADS —— 这是三轮排查后的结论】
 *
 * 症状：自定义显示屏 / 密码控制台 / 调试界面的**底纹完全不显示**，
 * 而同一个方法里 {@code fontRenderer.drawString} 画的字、原版按钮都正常。
 *
 * 事实对比（关键证据，不是猜的）：
 *   · **能显示**：DFC 屏幕外壳（TESR，{@code GL_QUADS + POSITION_COLOR}）、
 *     所有文字（{@code POSITION_TEX_COLOR}）、原版按钮（{@code POSITION_TEX}）；
 *   · **不显示**：这里原来的自绘填充 —— v3.7 是 {@code GL_TRIANGLES + POSITION_COLOR}，
 *     v3.8 改成 {@code GL_TRIANGLES + POSITION_TEX_COLOR}（绑白纹理），**两次都不可见**。
 * 两次唯一的共同点就是 **GL_TRIANGLES**。
 *
 * 所以现在：普通矩形直接调用原版 {@link Gui#drawRect}（和所有原版 GUI 同一条路，风险为零），
 * 圆角矩形用**逐行四边形**拼（每行一个 GL_QUADS），全部走 QUADS。
 * ==================================================================
 */
@SideOnly(Side.CLIENT)
public final class GuiDraw {

    /** 排错用：第一次真正提交时打一行日志（不刷屏） */
    private static boolean loggedOnce = false;

    private GuiDraw() {
    }

    /**
     * 实心矩形填充（ARGB）。
     *
     * 直接委托 {@link Gui#drawRect} —— 原版所有 GUI 的底纹、格子、tooltip 背景都走它，
     * 是这套渲染里唯一不需要再验证的路径。
     */
    public static void drawRect(int x1, int y1, int x2, int y2, int color) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        Gui.drawRect(x1, y1, x2, y2, color);
    }

    /**
     * 圆角矩形填充。
     *
     * 实现是**逐行四边形**：第 i 行按圆弧算一个左右内缩量，画一条 1px 高的矩形。
     * 全部顶点塞进同一个 {@code BufferBuilder}，一次 draw 提交（一屏面板约 150~240 个 quad，
     * 对 GUI 来说完全无压力）。
     *
     * 为什么不用三角扇：见类注释 —— GL_TRIANGLES 在本整合包的 GUI 阶段画不出来。
     */
    public static void drawRoundedRect(float x, float y, float w, float h, float radius, int color) {
        if (w <= 0.0F || h <= 0.0F) {
            return;
        }
        float r = Math.max(0.0F, Math.min(radius, Math.min(w, h) / 2.0F));

        // 没有圆角就直接交给原版，省一层
        if (r < 0.5F) {
            drawRect(Math.round(x), Math.round(y),
                    Math.round(x + w), Math.round(y + h), color);
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

        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        int rows = (int) Math.ceil(h);
        int drawn = 0;
        for (int i = 0; i < rows; i++) {
            float inset = GuiGeometry.rowInset(r, h, i);
            float x0 = x + inset;
            float x1 = x + w - inset;
            if (x1 - x0 < 0.5F) {
                continue;
            }
            float y0 = y + i;
            float y1 = y0 + 1.0F;
            // 顶点顺序照抄原版 Gui.drawRect：左下 -> 右下 -> 右上 -> 左上
            buf.pos(x0, y1, 0.0D).color(rr, gg, bb, a).endVertex();
            buf.pos(x1, y1, 0.0D).color(rr, gg, bb, a).endVertex();
            buf.pos(x1, y0, 0.0D).color(rr, gg, bb, a).endVertex();
            buf.pos(x0, y0, 0.0D).color(rr, gg, bb, a).endVertex();
            drawn++;
        }
        tess.draw();

        GlStateManager.enableTexture2D();

        if (!loggedOnce) {
            loggedOnce = true;
            com.jiezhimod.JiezhiMod.warn("[GuiDraw] first rounded fill: rows=" + drawn
                    + " color=#" + Integer.toHexString(color).toUpperCase());
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
