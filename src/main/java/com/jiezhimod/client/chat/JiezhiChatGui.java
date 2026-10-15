package com.jiezhimod.client.chat;

import com.jiezhimod.client.anim.AnimMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 自定义聊天栏：完全接管 GuiNewChat 的存储与绘制，加入三套动画。
 *
 * 为什么要替换实例而不是只用 RenderGameOverlayEvent.Chat 自绘：
 *   GuiChat 打开后的鼠标悬浮/点击走的是 GuiNewChat.getChatComponent()，
 *   如果只取消渲染、原版实例仍在，命中判定与视觉位置会对不上。
 *   直接把 GuiIngame 里那个 GuiNewChat 换成我们的子类，绘制与点击就天然一致。
 *
 * 生命周期（与原版 GuiNewChat 对齐，务必保持）：
 *   · 动画时钟 **永远**按真实时间推进。原版实现里"聊天打开时"不算淡出时间，
 *     如果照抄成"打开聊天就冻结时钟"，聊天期间新进来的消息 age 恒为 0，
 *     enterAlpha 永远等于 0 → 整条消息全透明，看起来就是"消息直接消失"。
 *   · 淡出只改 alpha，**绝不从 entries 里物理删除**。原版也是只保留行、
 *     靠 alpha 淡出，玩家还能用滚轮往上翻历史。物理删除会导致历史被清空。
 *   · 聊天打开时保持期失效（alpha 恒为 1），所以历史消息始终清晰可读，
 *     不会在你阅读时过期消失 —— 与原版 flag=true 时 l1=255 的行为一致。
 *
 * 布局：聊天框锚在屏幕底部，最新消息在最下面（和原版一致），旧消息往上堆；
 *      超过可见行数的部分自然被顶到屏幕外。
 *
 * 动画：
 *  1. 进场  —— 从底部往上弹起（easeOutBack，带一点点回弹）+ 淡入；
 *  2. 退场  —— 保持 10 秒后开始向左滑出并淡出；
 *  3. 打开聊天（T/指令）—— 可见消息从左往右依次滑入。
 */
@SideOnly(Side.CLIENT)
public class JiezhiChatGui extends GuiNewChat {

    /** 进场时长 */
    public static final long ENTER_MS = 280L;
    /**
     * 保持时长（10 秒，与原版 GuiNewChat 的 200 tick 对齐）。
     * 之前是 8 秒，比原版短，玩家会觉得消息"刚出来就没了"。
     */
    public static final long HOLD_MS = 10000L;
    /** 淡出（同时向左滑出）时长 */
    public static final long FADE_MS = 900L;
    /** 打开聊天时的左滑入时长 */
    public static final long OPEN_SLIDE_MS = 360L;
    /** 左滑入逐行错开 */
    public static final long OPEN_STAGGER_MS = 18L;
    /** 左滑出的距离（聊天坐标 px） */
    public static final float SLIDE_OUT_DIST = 44.0F;
    /** 历史保留条数 */
    public static final int MAX_HISTORY = 300;
    /** 行间距（在 FONT_HEIGHT=9 之上再加） */
    public static final int LINE_GAP = 1;
    /** 左边距（缩放后的 GUI 像素） */
    public static final int LEFT_MARGIN = 2;
    /**
     * 聊天底边相对 drawChat 原点的纵向偏移（屏幕像素，未乘 chatScale）。
     *
     * 关键：GuiIngame 是在 translate(0, scaledHeight-48, 0) 之后才调用 drawChat 的，
     * 所以本方法内部拿不到"绝对屏幕坐标"，原点已经在屏幕底部上方 48px 处。
     * 原版 GuiNewChat 就是在这个原点下再 translate(2, 8) 然后往上画。
     * 这里必须沿用同一基准，否则会把聊天整体推到屏幕外（表现为"消息完全看不见"）。
     */
    public static final int BOTTOM_ANCHOR_PX = 8;
    /**
     * GuiIngame 调用 drawChat 前把原点上移的距离（屏幕像素，未乘 chatScale）。
     * 原版 getChatComponent 里的 "-40" 就是 48-8 得来的，这里显式写出来便于对照。
     */
    public static final int CHAT_ORIGIN_UP_PX = 48;

    /**
     * 堆叠顺序开关。
     * true  = 先发送的在上方、最新的在下方（原版 MC 的顺序，默认）；
     * false = 最新的在最上面、旧的往下堆。
     */
    public static final boolean NEWEST_AT_BOTTOM = true;

    private final Minecraft mc;
    private final List<ChatEntry> entries = new ArrayList<ChatEntry>();
    private final List<String> sentMessages = new ArrayList<String>();

    /** 只按真实时间推进的"聊天时钟" —— 始终推进，绝不因聊天打开而冻结 */
    private long clockMs = 0L;
    private long lastRealMs = -1L;
    private long openSinceMs = -1L;
    private boolean wasOpen = false;

    private int scrollPos = 0;
    private int wrapWidth = -1;
    private long nextId = 1L;
    private List<Row> lastDrawn = Collections.emptyList();

    public JiezhiChatGui(Minecraft mcIn) {
        super(mcIn);
        this.mc = mcIn;
    }

    // ==================== 安装 ====================

    /**
     * 把 GuiIngame 里的原版聊天实例换成本类。
     *
     * 这里按"字段类型是 GuiNewChat"去找字段，而不是按字段名：
     * 生产环境（reobf 后）字段名是 SRG 名，写死 "persistantChatGUI" 会失效。
     *
     * @return 是否替换成功；失败时保留原版聊天（降级，不会崩）
     */
    public static boolean install(Minecraft mc) {
        if (mc == null || mc.ingameGUI == null) {
            return false;
        }
        if (mc.ingameGUI.getChatGUI() instanceof JiezhiChatGui) {
            return true;
        }
        Field target = null;
        for (Field f : GuiIngame.class.getDeclaredFields()) {
            if (f.getType() == GuiNewChat.class) {
                target = f;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        try {
            target.setAccessible(true);
            JiezhiChatGui gui = new JiezhiChatGui(mc);
            target.set(mc.ingameGUI, gui);
            return mc.ingameGUI.getChatGUI() == gui;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 消息接收 ====================

    @Override
    public void printChatMessage(ITextComponent component) {
        add(component, 0);
    }

    @Override
    public void printChatMessageWithOptionalDeletion(ITextComponent component, int chatLineId) {
        add(component, chatLineId);
    }

    private void add(ITextComponent component, int chatLineId) {
        if (component == null) {
            return;
        }
        if (chatLineId != 0) {
            deleteChatLine(chatLineId);
        }
        entries.add(new ChatEntry(nextId++, component, chatLineId, clockMs, wrap(component, currentWrapWidth())));
        trimHistory();
        // 聊天开着时来了新消息：把视角拉回最新，否则玩家会盯着被顶走的历史看
        if (getChatOpen() && scrollPos > 0) {
            scrollPos++;
        }
    }

    @Override
    public void deleteChatLine(int id) {
        Iterator<ChatEntry> it = entries.iterator();
        while (it.hasNext()) {
            if (it.next().lineId == id) {
                it.remove();
            }
        }
    }

    @Override
    public void clearChatMessages(boolean clearSentHistory) {
        entries.clear();
        lastDrawn = Collections.emptyList();
        scrollPos = 0;
        if (clearSentHistory) {
            sentMessages.clear();
        }
    }

    private void trimHistory() {
        while (entries.size() > MAX_HISTORY) {
            entries.remove(0);
        }
    }

    /** 历史条数（供 GUI / 自测断言用） */
    public int historySize() {
        return entries.size();
    }

    /**
     * 已过期（走完淡出）的消息"不算可见"，但**绝不从 entries 里删除**。
     * 删掉的话玩家滚上去就是空的 —— 这正是"历史消息看不到"的根因。
     * 只靠 alpha 归零 + MAX_HISTORY 环形淘汰，和原版只保留行、靠 alpha 淡出一致。
     */
    public int visibleCount() {
        int n = 0;
        for (ChatEntry e : entries) {
            if (!AnimMath.isGone(clockMs - e.bornMs, HOLD_MS, FADE_MS)) {
                n++;
            }
        }
        return n;
    }

    // ==================== 绘制 ====================

    @Override
    public void drawChat(int updateCounter) {
        Minecraft mc = this.mc;
        if (mc == null || mc.fontRenderer == null || mc.gameSettings == null) {
            return;
        }
        if (mc.gameSettings.chatVisibility == EntityPlayer.EnumChatVisibility.HIDDEN) {
            lastDrawn = Collections.emptyList();
            return;
        }

        boolean open = this.getChatOpen();
        advanceClock(open);
        ensureWrapped();

        List<Row> rows = buildRows();
        if (rows.isEmpty()) {
            lastDrawn = Collections.emptyList();
            return;
        }

        int maxLines = Math.max(1, this.getLineCount());
        int maxScroll = Math.max(0, rows.size() - maxLines);
        scrollPos = MathHelper.clamp(scrollPos, 0, maxScroll);
        int from = scrollPos;
        int to = Math.min(rows.size(), scrollPos + maxLines);

        float scale = this.getChatScale();
        int lineH = mc.fontRenderer.FONT_HEIGHT + LINE_GAP;
        float baseAlpha = mc.gameSettings.chatOpacity * 0.9F + 0.1F;
        long now = System.currentTimeMillis();
        boolean openAnim = open && openSinceMs > 0L && (now - openSinceMs) < OPEN_SLIDE_MS + rows.size() * OPEN_STAGGER_MS;
        float slideInDistance = Math.max(40.0F, this.getChatWidth() * 0.7F);

        // 原点已被 GuiIngame 平移到"屏幕底部上方 48px"，这里只做原版同样的锚点偏移，
        // 不能再用 ScaledResolution 的绝对高度去算，否则会二次偏移把聊天推出屏幕。
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) LEFT_MARGIN, (float) BOTTOM_ANCHOR_PX, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        GlStateManager.enableBlend();
        int bottom = 0;   // 锚点即底边，与原版 translate(2,8) 后的 y=0 一致

        List<Row> drawn = new ArrayList<Row>(to - from);
        for (int i = from; i < to; i++) {
            Row row = rows.get(i);
            ChatEntry e = row.entry;
            long age = clockMs - e.bornMs;
            int slot = i - from;              // 0 = 最下面那行（最新）
            float enterA = AnimMath.enterAlpha(age, ENTER_MS);
            // 聊天打开时保持期失效：alpha 恒为 1，历史消息不会在你阅读时过期消失
            // （与原版 GuiNewChat 里 flag=true 时 l1=255 的处理一致）
            float keepA = open ? 1.0F : AnimMath.fadeAlpha(age, HOLD_MS, FADE_MS);
            float alpha = MathHelper.clamp(enterA * keepA * baseAlpha, 0.0F, 1.0F);
            float yOff = AnimMath.enterResidual(age, ENTER_MS) * (lineH * 2.0F);
            // 打开聊天时不施加滑出位移，否则历史会整片偏到屏幕外
            float xOff = open ? 0.0F : AnimMath.slideOutOffset(age, HOLD_MS, FADE_MS, SLIDE_OUT_DIST);
            if (openAnim) {
                xOff += AnimMath.slideInOffset(now - openSinceMs, (to - from - 1 - slot),
                        OPEN_SLIDE_MS, OPEN_STAGGER_MS, slideInDistance);
            }
            int y = MathHelper.floor(rowY(slot, to - from, bottom, lineH) + yOff);
            row.y = y;
            row.xOff = xOff;
            row.alpha = alpha;
            drawn.add(row);
            drawRow(row, alpha);
        }

        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
        lastDrawn = drawn;
    }

    private void drawRow(Row row, float alpha) {
        Minecraft mc = this.mc;
        int a = (int) (alpha * 255.0F);
        if (a <= 2) {
            return;
        }
        int color = (a << 24) | 0xFFFFFF;
        int width = mc.fontRenderer.getStringWidth(row.text);
        int x = MathHelper.floor(row.xOff);
        // 背景：始终为聊天文字垫一层暗底，保证亮背景下也读得清
        drawRect(x - 1, row.y - 1, x + width + 2, row.y + mc.fontRenderer.FONT_HEIGHT, (a / 2) << 24);
        GlStateManager.enableBlend();
        mc.fontRenderer.drawStringWithShadow(row.text, (float) x, (float) row.y, color);
    }

    // ==================== 覆写的其它行为 ====================

    @Override
    public void refreshChat() {
        wrapWidth = -1;
        // 窗口尺寸变了，之前记录的命中坐标全部作废，必须清空
        lastDrawn = Collections.emptyList();
        ensureWrapped();
    }

    @Override
    public List<String> getSentMessages() {
        return this.sentMessages;
    }

    @Override
    public void addToSentMessages(String message) {
        if (message != null && !message.isEmpty()) {
            this.sentMessages.add(message);
        }
    }

    @Override
    public void resetScroll() {
        this.scrollPos = 0;
    }

    @Override
    public void scroll(int amount) {
        this.scrollPos += amount;
        if (this.scrollPos < 0) {
            this.scrollPos = 0;
        }
    }

    /** 当前实际画出来的行里，有多少条还是可见的（alpha 未归零）—— 供自测断言 */
    public int lastDrawnVisibleCount() {
        int n = 0;
        for (Row r : lastDrawn) {
            if (r.alpha > 0.01F) {
                n++;
            }
        }
        return n;
    }

    @Override
    public ITextComponent getChatComponent(int mouseX, int mouseY) {
        if (!this.getChatOpen() || lastDrawn.isEmpty() || this.mc == null) {
            return null;
        }
        ScaledResolution sr = new ScaledResolution(this.mc);
        int factor = sr.getScaleFactor();
        float scale = this.getChatScale();
        // 换算到 drawChat 的局部坐标：GuiIngame 已把原点挪到屏幕底部上方 48px，
        // 本类又往下挪了 BOTTOM_ANCHOR_PX，两段都要减掉（与 drawChat 里的变换对称）。
        int cx = MathHelper.floor((mouseX / factor - LEFT_MARGIN) / scale);
        int cy = MathHelper.floor((mouseY / factor - (CHAT_ORIGIN_UP_PX - BOTTOM_ANCHOR_PX)) / scale);
        int lineH = this.mc.fontRenderer.FONT_HEIGHT + LINE_GAP;
        for (Row row : lastDrawn) {
            if (row.alpha <= 0.01F) {
                // 已淡出的行不参与命中：否则会"点到看不见的文字"
                continue;
            }
            if (cy >= row.y - 1 && cy < row.y + lineH) {
                int width = this.mc.fontRenderer.getStringWidth(row.text);
                if (cx >= row.xOff - 1 && cx <= row.xOff + width + 1) {
                    return componentAtOffset(row.comp, (int) (cx - row.xOff));
                }
            }
        }
        return null;
    }

    /** 在该行内部按 x 偏移找到真正被点击的那个子组件（用于 URL / 建议名之类的点击） */
    private ITextComponent componentAtOffset(ITextComponent line, int x) {
        int total = 0;
        for (ITextComponent child : line) {
            total += this.mc.fontRenderer.getStringWidth(child.getUnformattedComponentText());
            if (total > x) {
                return child;
            }
        }
        return line;
    }

    // ==================== 内部工具 ====================

    /**
     * 一行在聊天坐标系里的 top。
     *
     * 【约定】slot 越小 = 越靠下（越接近底边），slot=0 是窗口里最下面那行。
     * 该约定必须与 buildRows() 产出的 index 顺序严格对应，改一处必须同步另一处。
     *
     * @param slot   该行在可见窗口中的序号，0 表示窗口里最下面那行（最新）
     * @param count  窗口内总行数
     * @param bottom 聊天框底边（窗口内最下面那行的下边缘）
     */
    public static int rowY(int slot, int count, int bottom, int lineH) {
        if (NEWEST_AT_BOTTOM) {
            return bottom - (slot + 1) * lineH;
        }
        // 最新的在最上面：窗口里最旧的那条贴着底边，其余往上排
        return bottom - (count - slot) * lineH;
    }

    /**
     * 推进动画时钟。
     *
     * 关键：clockMs **始终**按真实时间推进。之前写成"聊天打开时不推进"，
     * 结果聊天期间新进来的消息 age 恒为 0，enterAlpha 永远等于 0，
     * 整条消息全透明 —— 表现就是"消息直接消失、输入的文字看不到"。
     * 现在打开聊天时改为"保持期失效"（见 drawChat 的 keepA），时间照常走。
     */
    private void advanceClock(boolean open) {
        long real = System.currentTimeMillis();
        if (lastRealMs < 0L) {
            lastRealMs = real;
        }
        long delta = Math.min(1000L, Math.max(0L, real - lastRealMs));
        lastRealMs = real;
        clockMs += delta;
        if (open && !wasOpen) {
            openSinceMs = real;
        } else if (!open) {
            openSinceMs = -1L;
        }
        wasOpen = open;
    }

    /** 窗口宽度变了就重新排版（调用时机等价于原版 refreshChat） */
    private void ensureWrapped() {
        int w = Math.max(16, this.getChatWidth());
        if (wrapWidth == w) {
            return;
        }
        wrapWidth = w;
        for (ChatEntry e : entries) {
            e.lines = wrap(e.source, w);
        }
    }

    private int currentWrapWidth() {
        return wrapWidth > 0 ? wrapWidth : Math.max(16, this.getChatWidth());
    }

    private List<ITextComponent> wrap(ITextComponent component, int width) {
        if (width <= 1) {
            return Collections.singletonList(component);
        }
        List<ITextComponent> lines = GuiUtilRenderComponents.splitText(component, width,
                this.mc.fontRenderer, false, false);
        if (lines == null || lines.isEmpty()) {
            return Collections.singletonList(component);
        }
        return lines;
    }

    /** 可见行：列表下标 0 = 最新（画在最下面），下标越大越旧（越靠上） */
    /**
     * 把 entries 摊平成"行"的列表，顺序约定：**从下往上的视觉顺序**（index 0 = 最下面那行）。
     *
     * 【为什么同一条消息的折行要倒着加】
     * 行序在两处各自成立一次，叠加就会颠倒，必须在这里统一：
     *   · splitText 返回的 e.lines 是 [第1行, 第2行, ...]，即从上往下；
     *   · 而本列表的 index 0 被 rowY 当作"最下面一行"（y 最小的那行是 index 最大）。
     * 若按 e.lines 正序加入，第 1 行拿到小 index（画在最下面）、第 2 行拿到大 index（画在最上面），
     * 同一条消息内部就变成"第2行在第1行上方"——正好是转行看起来反了的原因。
     * 所以这里对每条消息的折行**逆序**加入：e.lines 的最后一行拿到最小 index（最下面），
     * 视觉上就恢复成"第1行在上、第2行在下"的正常阅读顺序。
     * 消息之间仍保持"新的在下"（外层 i 从大到小遍历 = 从新到旧）。
     */
    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<Row>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            ChatEntry e = entries.get(i);
            List<ITextComponent> lines = e.lines;
            for (int li = lines.size() - 1; li >= 0; li--) {
                ITextComponent comp = lines.get(li);
                rows.add(new Row(e, comp, comp.getFormattedText()));
            }
        }
        return rows;
    }

    // ==================== 数据 ====================

    private static final class ChatEntry {
        final long id;
        final ITextComponent source;
        final int lineId;
        final long bornMs;
        List<ITextComponent> lines;

        ChatEntry(long id, ITextComponent source, int lineId, long bornMs, List<ITextComponent> lines) {
            this.id = id;
            this.source = source;
            this.lineId = lineId;
            this.bornMs = bornMs;
            this.lines = lines;
        }
    }

    private static final class Row {
        final ChatEntry entry;
        final ITextComponent comp;
        final String text;
        int y;
        float xOff;
        float alpha;

        Row(ChatEntry entry, ITextComponent comp, String text) {
            this.entry = entry;
            this.comp = comp;
            this.text = text;
        }
    }
}
