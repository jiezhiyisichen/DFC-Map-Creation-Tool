package com.jiezhimod.client.notice;

import com.jiezhimod.client.anim.AnimMath;
import com.jiezhimod.client.util.GuiDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

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
    public static final int MARGIN_BOTTOM = 8;
    public static final int PAD_X = 8;
    public static final int PAD_Y = 6;
    public static final int GAP = 5;
    public static final int MIN_WIDTH = 150;
    public static final int MAX_WIDTH = 240;
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

        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();

        // 底部的那条最旧，向上依次变新 —— 先发送的在下方
        int cursor = sr.getScaledHeight() - MARGIN_BOTTOM;
        for (int i = list.size() - 1; i >= 0; i--) {
            NoticeEntry e = list.get(i);
            List<String> bodyLines = mc.fontRenderer.listFormattedStringToWidth(e.body, bodyWrapWidth());
            int width = cardWidth(mc.fontRenderer.getStringWidth(e.displayTitle()), bodyLines, mc.fontRenderer);
            Metrics m = new Metrics(width, lineH, bodyLines.size());
            int height = m.height(lineH, gap);
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

        // 1px 淡描边 + 纯黑半透明底（直角，不走圆角）
        GuiDraw.drawRect(cx - 1, cy - 1, cx + w + 1, cy + h + 1,
                GuiDraw.withAlpha(0x33FFFFFF, alpha));
        GuiDraw.drawRect(cx, cy, cx + w, cy + h, GuiDraw.withAlpha(0xF0000000, alpha * 0.82F));

        int textColor = GuiDraw.withAlpha(0xFFFFFFFF, alpha);
        mc.fontRenderer.drawStringWithShadow(e.displayTitle(), fx + PAD_X, fy + PAD_Y, textColor);

        float ty = fy + PAD_Y + lineH + gap;
        int bodyColor = GuiDraw.withAlpha(0xFFDDDDDD, alpha);
        for (String line : bodyLines) {
            // 1.12.2 的无阴影版本是 drawString(String, float, float, int, boolean)，
            // 三参整数版会丢掉浮点位置导致对齐跳动，所以正文也走浮点重载
            mc.fontRenderer.drawString(line, fx + PAD_X, ty, bodyColor, false);
            ty += lineH;
        }
    }
}
