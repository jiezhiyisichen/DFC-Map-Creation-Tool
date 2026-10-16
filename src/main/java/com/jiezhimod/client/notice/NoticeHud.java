package com.jiezhimod.client.notice;

import com.jiezhimod.client.anim.AnimMath;
import com.jiezhimod.client.util.GuiDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

/**
 * 右下角通知叠加层。
 *
 * 与聊天栏共用同一套**生命周期**（进入 / 保持 / 淡出），但退场方向不同：
 *  聊天栏向左滑出（它贴着屏幕左边），通知**向右弹出**（它贴着屏幕右边，
 *  向左滑会横穿整屏；向右像是被"收回"屏幕外，更符合临时提示的观感）。
 * 外观：直角（不走圆角）+ 纯黑半透明底，让它在游戏画面上更像一个浮层而不是 GUI 面板。
 * 堆叠顺序：先来的在下方，后来的压在上面。
 */
@SideOnly(Side.CLIENT)
public final class NoticeHud {

    public static final int MARGIN_RIGHT = 8;

    /**
     * 距屏幕底部的留白。
     *
     * 【为什么是 56 而不是 8】原版 HUD 最下面这几十像素全是有用的东西：
     *   快捷栏  y ≈ height - 22
     *   经验条  y ≈ height - 29
     *   生命 / 饥饿 / 护甲 y ≈ height - 39 / -49
     * 原来取 8，通知正好糊在快捷栏上 —— 玩家看不见自己选中了哪一格，
     * 也看不见血量和饥饿。抬到 56 就整块让开了，同时仍贴着屏幕右下角。
     */
    public static final int MARGIN_BOTTOM = 56;
    public static final int PAD_X = 8;
    public static final int PAD_Y = 6;
    public static final int GAP = 5;

    /**
     * 通知卡宽度区间。
     *
     * 【为什么收窄】原来上限 240，在 GUI 缩放 3 的 1080p 上等于横跨大半个屏幕，
     * 长正文一条就占掉小半张脸。收到 184 之后长文会自动折行，占的还是那点面积，
     * 但横向侵入明显变小。
     */
    public static final int MIN_WIDTH = 132;
    public static final int MAX_WIDTH = 184;
    public static final float SLIDE_OUT_DIST = 56.0F;
    public static final float POP_UP_PX = 18.0F;

    private NoticeHud() {
    }

    /** 一片通知卡的尺寸（正文已按可用宽度换行） */
    public static final class Metrics {
        public final int width;
        public final int titleHeight;
        public final int lineCount;

        Metrics(int width, int titleHeight, int lineCount) {
            this.width = width;
            this.titleHeight = titleHeight;
            this.lineCount = lineCount;
        }

        public int height(int fontHeight, int gap) {
            return PAD_Y + titleHeight + (lineCount > 0 ? gap : 0) + lineCount * fontHeight + PAD_Y;
        }
    }

    /**
     * 计算卡片宽度：标题与正文各自量宽，取较大者再夹在区间内。
     * 与绘制分离，方便自测。
     */
    public static int cardWidth(int titleWidth, List<String> bodyLines,
                                net.minecraft.client.gui.FontRenderer font) {
        int body = 0;
        for (String line : bodyLines) {
            body = Math.max(body, font.getStringWidth(line));
        }
        int w = Math.max(titleWidth, body) + PAD_X * 2;
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, w));
    }

    public static int bodyWrapWidth() {
        return MAX_WIDTH - PAD_X * 2;
    }

    public static void draw(Minecraft mc, ScaledResolution sr) {
        if (mc == null || sr == null || mc.fontRenderer == null) {
            return;
        }
        List<NoticeEntry> list = ClientNoticeManager.onScreen(System.currentTimeMillis());
        if (list.isEmpty()) {
            return;
        }
        int lineH = mc.fontRenderer.FONT_HEIGHT;
        int gap = 2;
        int right = sr.getScaledWidth() - MARGIN_RIGHT;
        long now = System.currentTimeMillis();
        int bottom = sr.getScaledHeight() - MARGIN_BOTTOM;

        // 顶上留出 1/3 屏不占用：一次来一长串通知时宁可少显示几条，
        // 也不能把整个画面铺满 —— 那比遮挡物品栏更糟。
        int topLimit = Math.max(16, sr.getScaledHeight() / 3);
        int budget = Math.max(lineH * 2, bottom - topLimit);

        // 【先量后画】把这一屏放得下的**最新的**若干条挑出来。
        // list[0] 是最新的，所以从头往后挑、超预算就停 —— 被丢掉的是更旧的那些。
        // 反过来做（铺到放不下才 break）会把刚推过来的那条丢掉，正好丢掉最该看的。
        List<NoticeEntry> keep = new ArrayList<NoticeEntry>();
        List<List<String>> keepLines = new ArrayList<List<String>>();
        int used = 0;
        for (int i = 0; i < list.size(); i++) {
            NoticeEntry e = list.get(i);
            List<String> bodyLines = mc.fontRenderer.listFormattedStringToWidth(e.body, bodyWrapWidth());
            int width = cardWidth(mc.fontRenderer.getStringWidth(e.displayTitle()), bodyLines, mc.fontRenderer);
            int height = new Metrics(width, lineH, bodyLines.size()).height(lineH, gap);
            int add = height + (keep.isEmpty() ? 0 : GAP);
            if (!keep.isEmpty() && used + add > budget) {
                break;
            }
            keep.add(e);
            keepLines.add(bodyLines);
            used += add;
        }

        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();

        // 底板那条最旧、向上依次变新 —— 先发送的在下方（与文档一致）。
        // keep 里 [0] 是最新的，所以从后往前铺。
        int cursor = bottom;
        for (int i = keep.size() - 1; i >= 0; i--) {
            NoticeEntry e = keep.get(i);
            List<String> bodyLines = keepLines.get(i);
            int width = cardWidth(mc.fontRenderer.getStringWidth(e.displayTitle()), bodyLines, mc.fontRenderer);
            int height = new Metrics(width, lineH, bodyLines.size()).height(lineH, gap);
            cursor -= height + GAP;
            drawCard(mc, right - width, cursor, width, height, e, bodyLines, now, lineH, gap);
        }

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawCard(Minecraft mc, int x, int y, int w, int h, NoticeEntry e,
                                 List<String> bodyLines, long now, int lineH, int gap) {
        long age = now - e.bornMs;
        float alpha = Math.min(AnimMath.enterAlpha(age, NoticeEntry.ENTER_MS),
                AnimMath.fadeAlpha(age, NoticeEntry.HOLD_MS, NoticeEntry.FADE_MS));
        if (alpha <= 0.01F) {
            return;
        }
        // 向右弹出：进入淡出期后 xOff 从 0 单调增到 +SLIDE_OUT_DIST
        float fx = x + AnimMath.slideOutOffset(age, NoticeEntry.HOLD_MS, NoticeEntry.FADE_MS,
                SLIDE_OUT_DIST, true);
        float fy = y + AnimMath.enterResidual(age, NoticeEntry.ENTER_MS) * POP_UP_PX;
        // 背景吸附整像素：不然 1px 描边落在半像素上，会随弹出动画时粗时细地闪。
        // 文字仍走浮点位置，保留逐帧的平滑位移（整数化会让它在滑动时一跳一跳）。
        int cx = Math.round(fx);
        int cy = Math.round(fy);

        // 半透明黑底（约 80% 不透明）+ 1px 淡描边，直角不走圆角。
        // 【为什么是这个不透明度】太透 → 压在亮色方块/水面上时字发糊看不清；
        // 太实 → 完全糊住后面的画面，跟开了个黑块没区别。0.8 是"看得出底下有什么、
        // 字也一眼能读"的位置，也正是需求里说的"稍微透明的黑底"。
        GuiDraw.drawRect(cx - 1, cy - 1, cx + w + 1, cy + h + 1,
                GuiDraw.withAlpha(0x40FFFFFF, alpha));
        GuiDraw.drawRect(cx, cy, cx + w, cy + h, GuiDraw.withAlpha(0xD9000000, alpha));

        int textColor = GuiDraw.withAlpha(0xFFFFFFFF, alpha);
        // 标题与正文都带阴影：黑底 + 阴影双重保底，任何背景上都读得清
        mc.fontRenderer.drawString(e.displayTitle(), fx + PAD_X, fy + PAD_Y, textColor, true);

        float ty = fy + PAD_Y + lineH + gap;
        int bodyColor = GuiDraw.withAlpha(0xFFDDDDDD, alpha);
        for (String line : bodyLines) {
            // 1.12.2 的无阴影版本是 drawString(String, float, float, int, boolean)，
            // 三参整数版会丢掉浮点位置导致对齐跳动，所以正文也走浮点重载
            mc.fontRenderer.drawString(line, fx + PAD_X, ty, bodyColor, true);
            ty += lineH;
        }
    }
}
