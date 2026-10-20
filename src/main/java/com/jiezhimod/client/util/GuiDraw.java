package com.jiezhimod.client.util;

import net.minecraft.client.gui.Gui;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

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
     * 【v3.9.4 起**退化成直角矩形**】实机反馈：3.9.2 用逐行四边形之后，
     * 密码控制台界面依然"鼠标出来了、界面一片空白"。
     * 与其继续在一个看不清的渲染问题上试探，不如把这条路径彻底收敛到
     * **原版 {@link Gui#drawRect}** —— 全游戏每一个 GUI 的底纹都走它，不可能出错。
     * 圆角等以后有实机条件了再用贴图做（贴图不经过 Tessellator）。
     */
    public static void drawRoundedRect(float x, float y, float w, float h, float radius, int color) {
        if (w <= 0.0F || h <= 0.0F) {
            return;
        }
        drawRect(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h), color);
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
