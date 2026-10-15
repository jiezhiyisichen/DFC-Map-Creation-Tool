package com.jiezhimod.client.notice;

import com.jiezhimod.client.util.GuiDraw;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 通知历史全屏界面（N 键打开，Esc 退出，滚轮滑动）。
 *
 * 最新的在最上面；每条都是一张圆角黑底卡片，标题在上、正文在下。
 * 上限 300 条由 ClientNoticeManager 保证，这里只负责翻阅。
 */
@SideOnly(Side.CLIENT)
public class GuiNoticeList extends GuiScreen {

    private static final int OUTER_MARGIN = 22;
    private static final int INNER_MARGIN = 10;
    private static final int HEADER_H = 24;
    private static final int FOOTER_H = 18;
    private static final int PAD_X = 8;
    private static final int PAD_Y = 6;
    private static final int GAP = 6;
    private static final int RADIUS = 6;
    private static final double WHEEL_STEP = 20.0D;
    /** 时间戳列预留宽度，避免标题被时间压到 */
    private static final int STAMP_SPACE = 46;

    private final List<NoticeEntry> entries;
    private int scroll = 0;
    private int contentHeight = 0;
    private int drawnCards = 0;

    public GuiNoticeList() {
        this.entries = ClientNoticeManager.historyNewestFirst();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            this.scroll += (wheel > 0 ? -WHEEL_STEP : WHEEL_STEP);
            clampScroll();
        }
        super.handleMouseInput();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int panelX = OUTER_MARGIN;
        int panelY = OUTER_MARGIN;
        int panelW = this.width - OUTER_MARGIN * 2;
        int panelH = this.height - OUTER_MARGIN * 2;
        GuiDraw.drawRoundedRect(panelX, panelY, panelW, panelH, 10.0F, 0xBB0A0A0F);

        String headline = I18n.format("gui.jiezhimod.notice.title")
                + "  (" + this.entries.size() + "/" + ClientNoticeManager.MAX_COUNT + ")";
        this.drawString(this.fontRenderer, headline, panelX + INNER_MARGIN, panelY + 8, 0xFFFFFFFF);
        GuiDraw.drawRoundedRect(panelX + INNER_MARGIN, panelY + HEADER_H - 6,
                panelW - INNER_MARGIN * 2, 1, 1.0F, 0x66333333);

        String hint = I18n.format("gui.jiezhimod.notice.hint");
        this.drawCenteredString(this.fontRenderer, hint, this.width / 2,
                panelY + panelH - FOOTER_H + 3, 0xFFAAAAAA);

        int listX = panelX + INNER_MARGIN;
        int viewportTop = panelY + HEADER_H;
        int viewportH = panelH - HEADER_H - FOOTER_H;
        int cardW = panelW - INNER_MARGIN * 2;
        clampScroll();

        int lineH = this.fontRenderer.FONT_HEIGHT;
        int wrapW = cardW - PAD_X * 2;
        int cursor = 0;
        int drawn = 0;
        for (NoticeEntry e : this.entries) {
            List<String> titleLines = this.fontRenderer.listFormattedStringToWidth(
                    e.displayTitle(), Math.max(16, wrapW - STAMP_SPACE));
            List<String> bodyLines = this.fontRenderer.listFormattedStringToWidth(
                    e.body, Math.max(16, wrapW));
            int h = PAD_Y * 2 + titleLines.size() * lineH
                    + (bodyLines.isEmpty() ? 0 : 2) + bodyLines.size() * lineH;
            int y = viewportTop - this.scroll + cursor;
            if (y + h >= viewportTop && y <= viewportTop + viewportH) {
                drawCard(e, listX, y, cardW, h, titleLines, bodyLines, lineH);
                drawn++;
            }
            cursor += h + GAP;
        }
        this.contentHeight = Math.max(0, cursor);
        this.drawnCards = drawn;

        if (this.entries.isEmpty()) {
            String empty = I18n.format("gui.jiezhimod.notice.empty");
            this.drawCenteredString(this.fontRenderer, empty, this.width / 2,
                    viewportTop + viewportH / 2 - 4, 0xFF888888);
        }

        int max = Math.max(0, this.contentHeight - viewportH);
        if (max > 0) {
            int trackX = listX + cardW - 3;
            int trackTop = viewportTop + 2;
            int trackBottom = viewportTop + viewportH - 2;
            GuiDraw.drawRoundedRect(trackX, trackTop, 3, trackBottom - trackTop, 1.5F, 0x33222222);
            int knobH = Math.max(18, viewportH * viewportH / Math.max(1, this.contentHeight));
            int knobY = trackTop + (int) ((trackBottom - trackTop - knobH) * ((double) this.scroll / (double) max));
            GuiDraw.drawRoundedRect(trackX, knobY, 3, knobH, 1.5F, 0xAAFFFFFF);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void clampScroll() {
        int max = Math.max(0, this.contentHeight - (this.height - OUTER_MARGIN * 2 - HEADER_H - FOOTER_H));
        if (this.scroll < 0) {
            this.scroll = 0;
        }
        if (this.scroll > max) {
            this.scroll = max;
        }
    }

    private void drawCard(NoticeEntry e, int x, int y, int w, int h,
                          List<String> titleLines, List<String> bodyLines, int lineH) {
        GuiDraw.drawRoundedRect(x, y, w, h, RADIUS, 0xE8141419);
        int ty = y + PAD_Y;
        for (String t : titleLines) {
            this.fontRenderer.drawStringWithShadow(t, x + PAD_X, ty, 0xFFFFFFFF);
            ty += lineH;
        }
        String stamp = new SimpleDateFormat("HH:mm:ss").format(new Date(e.bornMs));
        this.fontRenderer.drawString(stamp, x + w - PAD_X - this.fontRenderer.getStringWidth(stamp),
                y + PAD_Y, 0xFF777777);
        ty += 2;
        for (String b : bodyLines) {
            this.fontRenderer.drawString(b, x + PAD_X, ty, 0xFFCCCCCC);
            ty += lineH;
        }
    }

    // ==================== 便于自测 ====================

    public int drawnCards() {
        return drawnCards;
    }

    public int contentHeight() {
        return contentHeight;
    }

    public int scrollOffset() {
        return scroll;
    }

    public void setScroll(int value) {
        this.scroll = Math.max(0, value);
    }

    public int entryCount() {
        return entries.size();
    }
}
