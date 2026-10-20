package com.jiezhimod.client.dfc;

import com.jiezhimod.dfc.DfcFormat;
import com.jiezhimod.dfc.DfcSnapshot;
import com.jiezhimod.dfc.DfcWatch;
import com.jiezhimod.dfc.ScreenLayout;
import com.jiezhimod.keypad.KeypadLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 显示器画面 —— 用 AWT 把界面画到一张 BufferedImage 上，再喂给 GL 纹理。
 *
 * 【为什么用 AWT 画，而不是用 MC 的 FontRenderer 在 3D 里直接铺字】
 * 1. 3D 空间里画文字要保持朝向、要处理透视形变，且 MC 字体只有 1 种像素字形，
 *    做不出"液晶面板"的层次（不同字号的数值、进度条、圆环仪表）；
 * 2. 整屏一次性画成纹理后，渲染时只是一个 quad —— 十几个显示器同屏也不会掉帧；
 * 3. 数据刷新频率远低于帧率（0.2s 一次），CPU 绘制摊到每一帧几乎为零。
 *
 * 【字号必须走逻辑量纲 —— 这是"字太小"的根因】
 * 本类的绘制原语（rect/line/oval/text 的**位置**）全部是逻辑坐标，内部乘 SS 变物理。
 * 但早期版本的字号是**物理像素**（{@code mono(6)} 就是 6px），没乘 SS。
 * 于是 SS 从 2 提到 3 时，纹理密度涨了 50%、文字却一点没变 —— 相对缩小了 1.5 倍，
 * 在游戏里就是"字太小、糊成一团"。
 * 现在统一由 {@link #fCjk}/{@link #fMono} 系列把逻辑字号乘 SS，
 * 所有字号都可以照"逻辑像素"来理解（逻辑 10 号 ≈ 屏上 4.7 倍像素）。
 *
 * 【中文字体】等宽英文字体（Consolas 等）不含汉字，用它画中文会得到一排方块。
 * 中文一律走 {@link ScreenFonts#cjk}/{@link ScreenFonts#cjkBold}，
 * 数值/ASCII 才走等宽族 —— 两边混排是刻意的：数值保持终端等宽感，
 * 标签用中文，一眼能读懂每个数据是什么。
 */
@SideOnly(Side.CLIENT)
public final class ScreenCanvas {

    /** 逻辑布局尺寸 */
    private static final int LW = ScreenLayout.W;
    private static final int LH = ScreenLayout.H;
    /**
     * 超采样倍数。
     *
     * 2 倍（512x384）在游戏里其实**不够**：屏幕是 4x3 格，玩家在常见距离下
     * 面板会占到 700+ 像素宽，于是纹理被放大 1.4 倍左右，再叠加 GL_LINEAR 的
     * 双线性放大 —— 小字直接糊掉。提到 3 倍后纹理 768x576，
     * 与屏幕实际占用的像素数基本 1:1，放大模糊消失。
     * 上限只受显存限制：每块屏 768*576*4 ≈ 1.8MB，多屏场景也吃得消。
     */
    private static final int SS = 3;
    /** 实际纹理尺寸 */
    public static final int TEX_W = LW * SS;
    public static final int TEX_H = LH * SS;

    // ==================== 配色（深色液晶面板） ====================
    private static final int BG = 0xFF04070B;
    private static final int PANEL = 0xFF0B1219;
    private static final int LINE = 0xFF1C3648;
    private static final int TXT = 0xFFDCE8F0;
    private static final int TXT_DIM = 0xFF7E97A8;
    private static final int OK = 0xFF52E08A;
    private static final int WARN = 0xFFFFC13D;
    private static final int CRIT = 0xFFFF4B4B;
    private static final int CYAN = 0xFF48D6FF;
    private static final int ACCENT = 0xFFFF6BD6;
    /**
     * 圆盘上"潜在增益"指针的颜色。
     *
     * 【为什么单独一个色】圆盘的底色/刻度环是随崩溃程度变色的（绿→黄→红），
     * 指针如果也用这三个色就会和刻度环糊在一起，分不清"哪根是进度、哪根是指针"。
     * 淡金偏白，在深蓝面板和红/绿两种主题下都看得出来。
     */
    private static final int NEEDLE = 0xFFFFE9A8;

    // ==================== 崩溃告警配色 ====================
    /**
     * 核心一开始崩溃（{@code collapsing > 0}），整块屏立刻切进"告警主题"：
     * 面板边框/分隔线转暗红、顶栏底色变深红、四边加一圈闪烁的红色描边，
     * 并且顶部中央闪一个 "!!"。中栏那个核心圆盘则**立刻变黄**。
     *
     * 【为什么边框和文字要分开两档红】纯红文字在深色底上对比度其实不高，
     * 所以"结构线"用暗红（ALERT_LINE）、"字"用亮红（ALERT_TXT），
     * 远看是一整块发红的屏，近看每个读数仍然读得清。
     */
    private static final int ALERT_LINE = 0xFF7E1A1A;
    private static final int ALERT_BG = 0xFF2A0B0B;
    private static final int ALERT_TXT = 0xFFFF8A8A;

    // 熔毁报错页单独一套更"坏"的红：整屏底色比告警态更暗（屏幕像是烧了），
    // 顶部故障条比底色亮一档当作分隔，正文用中间调、故障码用纯 CRIT。
    private static final int FAULT_BG = 0xFF1A0606;
    private static final int FAULT_BAR = 0xFF40100F;
    private static final int FAULT_TXT = 0xFFC48A8A;
    /** "!!" 的闪烁周期（毫秒）—— 亮/灭各一半。真源在 ScreenLayout，见那里的对齐说明 */
    private static final long BLINK_MS = ScreenLayout.ALERT_BLINK_MS;

    // ==================== 页面 ====================
    // 常量真源在 ScreenLayout（纯函数类，离线自测能断言），这里只是方便画布内部引用。
    /** 页面 0：核心遥测（温度 / 稳定度 / 能量 / 崩溃圆盘 / 事件日志） */
    public static final int PAGE_CORE = ScreenLayout.PAGE_CORE;
    /** 页面 1：部件遥测（发射器 / 接收器 / 燃料 / 燃料喷射器 / 积蓄能量 / 能量爆发） */
    public static final int PAGE_COMPONENTS = ScreenLayout.PAGE_COMPONENTS;
    /** 页面 2：自定义数据列表（最多 8 项，外部设备读数） */
    public static final int PAGE_CUSTOM = ScreenLayout.PAGE_CUSTOM;

    // ==================== 布局（逻辑坐标，256x192） ====================
    /** 顶栏高度 */
    private static final int HEADER_H = 19;
    private static final int ROW_TOP = 23;
    private static final int ROW_BOTTOM = 188;

    /** 左栏：温度 / 稳定度 / 能量 */
    private static final int COL_L_X = 4;
    private static final int COL_L_W = 82;
    /** 中栏：圆盘 / 运行状态 */
    private static final int COL_M_X = 90;
    private static final int COL_M_W = 70;
    /** 右栏：事件日志 */
    private static final int COL_R_X = 164;
    private static final int COL_R_W = 88;

    private final BufferedImage image;
    private final int[] pixels;
    private final Graphics2D g;

    private DynamicTexture texture;
    /** 自管的 GL 纹理 id（与 texture.getGlTextureId() 一致，便于本类内直用） */
    private int texId;
    /** 自管上传缓冲：直传用，不进 MC 的全局共享 DATA_BUFFER（那玩意会被别的 mod 弄脏） */
    private ByteBuffer uploadBuf;
    /** 注册到 TextureManager 用的唯一键，由方块实体按坐标设置 */
    private ResourceLocation location = new ResourceLocation("jiezhimod", "dfc_screen_default");
    private boolean pixelDirty = true;
    /** 上一次真正重绘时的时间桶，用来避免每帧都重画 */
    private long lastBucket = Long.MIN_VALUE;
    /** 强制重传保险：资源包重载会清掉 GL 纹理内容，隔一阵补一次 */
    private long lastUploadMs;

    /** 当前帧是否处于"核心崩溃告警"状态（由 {@link #drawMain} 设置） */
    private boolean alert;
    /** 本帧面板结构线（边框/分隔线）的颜色：正常 LINE、告警 ALERT_LINE */
    private int themeLine = LINE;
    /** 本帧区块标题的颜色：正常暗灰、告警淡红 */
    private int themeTitle = TXT_DIM;
    /** 本帧的时间相位（开机态 = sinceOnMs）。闪烁动画一律用它，保证同一时刻可复现 */
    private long framePhaseMs;

    public ScreenCanvas() {
        this.image = new BufferedImage(TEX_W, TEX_H, BufferedImage.TYPE_INT_ARGB);
        this.pixels = new int[TEX_W * TEX_H];
        this.g = image.createGraphics();
        this.g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        this.g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        this.g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
    }

    // ==================== 字体（逻辑字号 -> 物理字号） ====================

    private static Font fCjk(int logical) {
        return ScreenFonts.cjk(logical * SS);
    }

    private static Font fCjkBold(int logical) {
        return ScreenFonts.cjkBold(logical * SS);
    }

    private static Font fMono(int logical) {
        return ScreenFonts.mono(logical * SS);
    }

    private static Font fMonoBold(int logical) {
        return ScreenFonts.monoBold(logical * SS);
    }

    // ==================== 对外 ====================

    /**
     * 按当前状态重绘画布（CPU 侧，无 GL 调用，可安全地在客户端 tick 里跑）。
     *
     * 【刷新率 0.2 秒】主界面每 200ms 重画一帧，与服务端的推送周期（4 tick）对齐。
     * 开机动画（进度条、光标闪烁）与关机画面的呼吸环都依赖连续相位，
     * 所以这两个阶段仍按 50ms 重画 —— 它们只持续一两秒，代价可以忽略。
     *
     * @param sinceOnMs 从"按下开机"到现在经过的毫秒
     * @param powered   是否处于开机状态
     * @param data      最新数据快照
     * @param events    事件日志（最新在最后）
     * @param page      显示哪一页，取 {@link #PAGE_CORE} / {@link #PAGE_COMPONENTS}
     */
    public void render(long sinceOnMs, boolean powered, DfcSnapshot data, List<String> events, int page) {
        render(sinceOnMs, powered, data, events, page, -1L);
    }

    /**
     * 同上，外加熔毁报错页。
     *
     * @param faultElapsedMs 报错页已经显示了多少毫秒；**&lt; 0 表示这一帧没有报错页**。
     *                       报错页（{@link #drawFault}）优先于开机自检和主界面 ——
     *                       屏幕"已经坏了"，再去画实时读数没有意义。
     */
    public void render(long sinceOnMs, boolean powered, DfcSnapshot data, List<String> events, int page,
                       long faultElapsedMs) {
        long step = 50L;
        if (powered && faultElapsedMs < 0L
                && ScreenLayout.phaseAt(sinceOnMs) == ScreenLayout.PHASE_MAIN) {
            // 主界面按这台屏自己的刷新率重绘。开机自检/控制台/报错页仍用 50ms ——
            // 那几段是"动画"，调慢了会像卡住。
            step = this.mainStepMs;
        }
        long phase = powered ? sinceOnMs : paintMs();
        this.framePhaseMs = phase;
        long bucket = phase / step;
        if (bucket == lastBucket && !pixelDirty) {
            return;
        }
        lastBucket = bucket;

        // 主题色每帧复位：只有主界面（且在崩溃）才会把它改成告警红
        this.alert = false;
        this.themeLine = LINE;

        g.setColor(new Color(BG, true));
        g.fillRect(0, 0, TEX_W, TEX_H);

        if (!powered) {
            drawOff();
        } else if (faultElapsedMs >= 0L) {
            drawFault(faultElapsedMs);
        } else {
            int p = ScreenLayout.phaseAt(sinceOnMs);
            switch (p) {
                case ScreenLayout.PHASE_BOOT:
                    drawBoot(sinceOnMs);
                    break;
                case ScreenLayout.PHASE_CONSOLE:
                    drawConsole(sinceOnMs);
                    break;
                default:
                    if (page == PAGE_COMPONENTS) {
                        drawComponents(data);
                    } else if (page == PAGE_CUSTOM) {
                        drawCustom(data);
                    } else {
                        drawMain(data, events);
                    }
                    break;
            }
        }

        drawScanlines();
        // 画完就把像素抓出来；GL 上传留给渲染线程
        image.getRGB(0, 0, TEX_W, TEX_H, pixels, 0, TEX_W);
        pixelDirty = true;
    }

    /** 默认主界面重绘周期（毫秒），取自 ScreenLayout，与服务端 REFRESH_TICKS 共用同一常量 */
    public static final long REFRESH_MS = ScreenLayout.REFRESH_MS;

    /**
     * 这台屏主界面的实际重绘周期（毫秒）。由 TESR 按方块实体里的刷新率设置。
     *
     * 【为什么可调】每重绘一次就要把 768×576 的画布抓成 int[] 再整张上传纹理，
     * 四块屏同时以 20Hz 刷新时这部分开销非常可观（玩家实测掉到 9 FPS）。
     * 部件页 / 自定义屏的读数变化本来就慢，调到 100~200ms 肉眼几乎看不出差别。
     */
    private long mainStepMs = REFRESH_MS;

    /** 设置主界面重绘周期；下限 50ms，避免被填成 0 之后每帧重绘 */
    public void setMainStepMs(long ms) {
        this.mainStepMs = Math.max(50L, ms);
    }

    /**
     * 把像素上传到 GL 纹理。**必须在渲染线程调用**（内部会绑纹理、调 glTexSubImage）。
     *
     * 【为什么不用 DynamicTexture.updateDynamicTexture()】
     * 诊断日志铁证：纹理已按 GL_RGBA8 正确分配、CPU 侧画布有内容、
     * 绑定与纹理参数全对，但显存回读恒为 0 —— 说明 MC 的
     * {@code TextureUtil.uploadTexture} 这条路径在本整合包里失效。
     * 它的实现依赖一个**全局静态的 DATA_BUFFER**（{@code TextureUtil.DATA_BUFFER}，
     * IntBuffer），OptiFine / 其他 mod 也会碰它，一旦 position/limit 被弄脏，
     * glTexSubImage2D 就从错误偏移取数、或直接报 1282 静默失败。
     * 因此这里改为**自管缓冲直传**：自己 newDirect ByteBuffer，自己调
     * glTexSubImage2D，不经过任何共享状态。
     */
    public void uploadIfDirty(long nowMs) {
        boolean forced = nowMs - lastUploadMs > 4000L;
        if (!pixelDirty && !forced) {
            return;
        }
        ensureTexture();
        if (this.uploadBuf != null) {
            // 把自己持有的像素按 GL_BGRA/UNSIGNED_BYTE 写进私有 direct buffer。
            // Windows 是小端序：int 0xAARRGGBB 落字节正好是 B,G,R,A —— 与 GL_BGRA 匹配。
            this.uploadBuf.clear();
            this.uploadBuf.asIntBuffer().put(pixels);
            this.uploadBuf.position(0).limit(pixels.length * 4);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(this.texId);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, TEX_W, TEX_H,
                    GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, this.uploadBuf);
            applyTextureParams();
        }
        pixelDirty = false;
        lastUploadMs = nowMs;
    }

    /**
     * 建纹理并自行分配显存。**不用 DynamicTexture 的上传路径**，因为它的
     * uploadTexture 走全局共享缓冲（见 uploadIfDirty 的说明）。这里完全自管：
     * glGenTextures + glTexImage2D 分配 + 之后 glTexSubImage2D 直传。
     */
    private void ensureTexture() {
        if (this.texId > 0) {
            return;
        }
        // 【先建容器，再分配】DynamicTexture 只是个 GL id 容器：
        // 构造时它会自己 glGenTextures + allocateTexture。我们随后强绑我们的 id，
        // 保证 drawPanel 用到的 id 与像素通道（uploadBuf）落在同一张纹理上。
        this.texture = new DynamicTexture(TEX_W, TEX_H);
        this.texId = this.texture.getGlTextureId();

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(this.texId);
        // 显式按 GL_RGBA8 重新分配（DynamicTexture 分配的格式由驱动决定，
        // 这里统一成 RGBA8，配合下面 GL_BGRA/UNSIGNED_BYTE 的直传）
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, TEX_W, TEX_H, 0,
                GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        applyTextureParams();

        // 私有 direct buffer：每像素 4 字节，容量固定，永不与别的 mod 共享
        this.uploadBuf = ByteBuffer.allocateDirect(TEX_W * TEX_H * 4)
                .order(ByteOrder.nativeOrder());

        // 注册到 TextureManager，保证资源包重载时这张纹理一起被管理/释放
        Minecraft.getMinecraft().getTextureManager().loadTexture(this.location, this.texture);
        this.pixelDirty = true;
        this.lastUploadMs = 0L;
    }

    /**
     * 非 2 的幂纹理必须用 CLAMP 包裹 + 非 mipmap 采样，否则部分驱动会判定为
     * "纹理不完整"。画布 768x576 两边都不是 2 的幂，所以每次上传后都重设一遍。
     */
    private void applyTextureParams() {
        if (this.texture == null) {
            return;
        }
        GlStateManager.bindTexture(this.texture.getGlTextureId());
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    /**
     * 供 drawPanel 取纹理 id 的句柄。
     *
     * 返回的 DynamicTexture 只是一个"GL id 容器"：它的像素数组我们不使用，
     * 真正的像素通道是 {@link #uploadBuf} + 自管的 glTexSubImage2D。
     * 之所以仍要 DynamicTexture 这个类型，是因为 TextureManager.loadTexture
     * 只接受 AbstractTexture，用它来挂注册/释放生命周期最省事；
     * 但**绝不能**再调它的 updateDynamicTexture()（会走失效的全局共享缓冲）。
     */
    public DynamicTexture texture() {
        ensureTexture();
        return this.texture;
    }

    /** 传给 TextureManager 的唯一键；用方块坐标保证多块屏幕各有一张自己的纹理 */
    public void setLocation(ResourceLocation loc) {
        if (loc != null) {
            this.location = loc;
        }
    }

    /** 释放 GL 纹理。方块实体失效（拆掉屏幕 / 换维度卸载区块）时必须调用 */
    public void dispose() {
        if (this.texId > 0) {
            // 先把 TextureManager 里的记录摘掉（它 release 时会再删一次，所以先置 0 防重复）
            try {
                Minecraft.getMinecraft().getTextureManager().deleteTexture(this.location);
            } catch (Throwable ignored) {
                // 资源包重载期间可能已被清掉，忽略
            }
            if (this.texId > 0) {
                GL11.glDeleteTextures(this.texId);
            }
        }
        this.texture = null;
        this.texId = 0;
        this.uploadBuf = null;
        this.pixelDirty = true;
    }

    /** 外部数据变化时催一次重绘（不然主界面要等下一个刷新周期） */
    public void invalidate() {
        lastBucket = Long.MIN_VALUE;
        this.pixelDirty = true;
    }

    /**
     * 诊断用：CPU 侧画布内容概况（非黑像素数 / 最大亮度 / 纹理 id）。
     * 用来区分"画布就没画出来"和"画布画了但没传到 GL"——两者症状都是黑屏。
     */
    public String cpuStats() {
        int nonBlack = 0;
        int maxLum = 0;
        for (int p : pixels) {
            int lum = Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255));
            if (lum > 8) {
                nonBlack++;
            }
            if (lum > maxLum) {
                maxLum = lum;
            }
        }
        return "nonBlack=" + nonBlack + "/" + pixels.length + " maxLum=" + maxLum
                + " texId=" + (this.texture == null ? -1 : this.texture.getGlTextureId());
    }

    // ==================== 画面：关机 ====================

    /**
     * 关机画面。
     *
     * 【必须做到"一眼看得出这是块屏，只是关着"】
     * 屏挂在天花板上，离地 3 格、4 格宽，玩家多半是从两三层楼外抬头扫一眼。
     * 原来这里画的是近黑底 + 一行极小的暗灰字，实际游戏里几乎分辨不出来 ——
     * 结果就是"屏幕全黑 = 渲染坏了"的误判。所以：底色提到可见的深蓝灰、
     * 文字加亮放大、四边加一圈自发光描边、面板中央画一个显眼的待机标记。
     */
    private void drawOff() {
        rect(0, 0, LW, LH, 0xFF11161C);
        glowFrame(LW, LH, 0xFF2E6E8C);

        textCenter("待机", LH / 2 - 24, TXT_DIM, fCjk(12));
        textCenter("无信号", LH / 2 + 2, CYAN, fCjkBold(18));
        textCenter("右键开机", LH / 2 + 26, 0xFF7FB4CC, fCjk(10));

        // 中央待机标记：一个慢速呼吸的圆环，静置时也在动，说明"这是活的屏"
        int cx = LW / 2;
        int cy = LH - 38;
        int r = 11;
        int pulse = (int) (5.5D + 3.5D * Math.sin(paintMs() / 700.0D));
        ovalStroke(cx - r, cy - r, r * 2, r * 2, (pulse << 24) | 0x2E6E8C, SS);
        ovalFill(cx - 2, cy - 2, 4, 4, CYAN);

        rect(6, LH - 12, LW - 12, 2, 0xFF16222C);
        rect(6, LH - 12, (LW - 12) / 2, 2, 0xFF2E6E8C);
    }

    /** 四边描边（逻辑坐标），用于关机画面的可视边界 */
    private void glowFrame(int w, int h, int argb) {
        rect(0, 0, w, 2, argb);
        rect(0, h - 2, w, 2, argb);
        rect(0, 0, 2, h, argb);
        rect(w - 2, 0, 2, h, argb);
    }

    /** 壁钟毫秒，供关机画面的呼吸动画使用 */
    private long paintMs() {
        return System.currentTimeMillis();
    }

    // ==================== 画面：开机 ====================

    private void drawBoot(long ms) {
        int cx = LW / 2;
        // 淡淡的径向底光
        for (int i = 6; i >= 1; i--) {
            int a = 6 + i * 3;
            oval(cx - i * 12, LH / 2 - 30 - i * 4, i * 24, i * 10, (a << 24) | 0x1B4A6E);
        }

        textCenter("节枝OS", LH / 2 - 24, TXT, fCjkBold(30));
        textCenter("节枝显示系统", LH / 2 + 4, CYAN, fCjk(11));
        textCenter("J I E Z H I   D I S P L A Y", LH / 2 + 20, 0xFF3E7A99, fMono(7));

        // 底部进度条：1.1 秒走满
        int bw = 140;
        int bx = cx - bw / 2;
        int by = LH - 34;
        float p = Math.min(1.0F, ms / (float) ScreenLayout.BOOT_MS);
        rect(bx, by, bw, 4, 0xFF12202B);
        rect(bx, by, Math.round(bw * p), 4, CYAN);

        textCenter("版本 1.4.2", LH - 15, TXT_DIM, fCjk(9));
        if (ScreenLayout.bootCursorVisible(ms)) {
            rect(cx + 30, LH - 22, 4, 8, OK);
        }
    }

    // ==================== 画面：控制台 ====================

    private void drawConsole(long ms) {
        int y = 15;
        text("启动自检", 6, y, CYAN, fCjk(10));
        hline(5, y + 4, LW - 10, LINE);
        y += 14;

        // 开机日志保留英文 —— 它模仿的是 BIOS 自检，英文才有那个味道；
        // 而且这一屏只闪 4 秒，不是日常读数的地方。
        int n = ScreenLayout.consoleLineCount(ms);
        int cursorLine = ScreenLayout.consoleCursorLine(ms);
        Font f = fMono(7);
        for (int i = 0; i < n; i++) {
            String line = ScreenLayout.BOOT_LOG[i];
            int color = TXT;
            if (line.indexOf("OK") > 0) {
                color = OK;
            } else if (line.indexOf("[bound]") > 0) {
                color = CYAN;
            } else if (line.startsWith("  ")) {
                color = TXT_DIM;
            }
            text(line, 6, y, color, f);
            if (i == cursorLine && ScreenLayout.bootCursorVisible(ms)) {
                rectRight(6 + textWidthLog(line, f) + 2, y - 6, 4, 7, TXT);
            }
            y += 11;
            if (y > LH - 10) {
                break;
            }
        }
    }

    // ==================== 画面：熔毁报错 ====================

    /**
     * 熔毁报错页。
     *
     * 需求："在进入熔毁阶段时，显示全屏报错301，与核心失去连接，等待4秒后，重启屏幕"。
     * 触发点是**坍缩度从 0 变成 &gt;0** 的那一瞬间（见 {@code TileDfcScreen.applyServerState}），
     * 也就是核心开始炸的那一刻 —— 这一刻之后屏幕"和数据源失去联系"，
     * 所以这一页**一个实时读数都不画**，就是一个死掉的终端。
     *
     * 整页只用传进来的 {@code ms}（报错页已经显示多久）驱动，不读挂钟 ——
     * 这样离屏导出两帧能拿到"刚开始"和"快重启了"两张不一样的图。
     */
    private void drawFault(long ms) {
        int cx = LW / 2;
        rect(0, 0, LW, LH, FAULT_BG);
        glowFrame(LW, LH, CRIT);

        // 顶部故障条
        rect(0, 0, LW, 20, FAULT_BAR);
        text("!! 系统故障", 6, 14, ALERT_TXT, fCjk(11));
        textRight("SYSTEM FAULT", LW - 8, 14, FAULT_TXT, fMono(9));

        // 故障码：整块屏最显眼的东西，抬头一眼就能读到
        textCenter(String.valueOf(ScreenLayout.FAULT_CODE), 78, CRIT, fMonoBold(46));
        textCenter(ScreenLayout.FAULT_TEXT, 106, ALERT_TXT, fCjkBold(17));

        hline(46, 120, LW - 92, ALERT_LINE);
        textCenter("数据链路已中断", 136, FAULT_TXT, fCjk(10));

        // 重启倒计时：把"等 4 秒"这件事画出来，免得玩家以为是死机
        int bw = 160;
        int bx = cx - bw / 2;
        int by = 158;
        float p = Math.min(1.0F, ms / (float) ScreenLayout.FAULT_MS);
        rect(bx, by, bw, 4, FAULT_BAR);
        rect(bx, by, Math.round(bw * p), 4, CRIT);

        long left = Math.max(0L, ScreenLayout.FAULT_MS - ms);
        // 【必须用 CJK 字族】这一行是"中文 + 数字"混排；等宽英文字体不含汉字，
        // 用 fMono 画出来就是四个豆腐块（离屏导出第一版就是这么翻车的）。
        textCenter("正在重启 · " + String.format(java.util.Locale.ROOT, "%.1fs", left / 1000.0D),
                by + 16, FAULT_TXT, fCjk(9));
        if (ScreenLayout.bootCursorVisible(ms)) {
            rect(cx + 58, by + 8, 4, 8, CRIT);
        }
    }

    // ==================== 画面：主界面 ====================

    private void drawMain(DfcSnapshot d, List<String> events) {
        // 【核心开始崩溃 = 整块屏进入告警态】
        // collapsing 是 0~1 的熔毁进度（作者代码 clamp(1 - explosionIn/120, 0, 1)），
        // 只要它一离开 0 就说明"已经在崩了"，此刻立刻：中栏核心变黄 + 面板转红 + 顶上闪 "!!"。
        // 判定本身走 ScreenLayout.alertActive()，好让离线自测能钉住"未连接 / 未到工作温度不许报警"。
        this.alert = ScreenLayout.alertActive(d);
        this.themeLine = this.alert ? ALERT_LINE : LINE;
        this.themeTitle = this.alert ? ALERT_TXT : TXT_DIM;

        drawHeader(d, null);

        // 【离线态】反应堆温度不到 100°C（含刚放下、已停堆降温）时，全屏改画"离线"页。
        // 为什么不是"照常画数据、只加个角标"：冷堆的每一项读数都是 0
        // （稳定度 0 / 能量 0 / 坍缩 0），照常画出来就是满屏的 0 和绿色"正常"，
        // 操作员根本分不清"堆没开"和"堆在正常运行但负载为零"。
        if (ScreenLayout.offline(d)) {
            drawOffline(d);
            return;
        }

        drawTempBlock(COL_L_X, ROW_TOP, COL_L_W, d);            // 23..77
        drawStabBlock(COL_L_X, 83, COL_L_W, d);                 // 83..127
        drawEnergyBlock(COL_L_X, 133, COL_L_W, d);              // 133..188

        drawDial(COL_M_X, ROW_TOP, COL_M_W, d);                 // 23..103
        drawRuntimeBlock(COL_M_X, 109, COL_M_W, d);             // 109..188

        drawEventLog(COL_R_X, ROW_TOP, COL_R_W, events);        // 23..188
    }

    /**
     * 离线页：反应堆还没到工作温度。
     *
     * 只给三样"判断为什么离线"必需的信息 —— 温度（离 100°C 还有多远）、
     * 稳定度、运行时长（温度归零后已清零），外加一句门槛提示。
     * 稳定度这里显示的就是 0，但**不是"危急"**（需求原话："0 度时稳定度为 0 时不显示危急"），
     * 所以这一页一律用中性灰，不出现任何红色告警元素。
     */
    private void drawOffline(DfcSnapshot d) {
        textCenter("离线", 92, WARN, fCjkBold(30));
        textCenter("OFFLINE", 114, TXT_DIM, fMono(12));

        hline(40, 126, LW - 80, themeLine);

        int iw = 120;
        int x = (LW - iw) / 2;
        row(x, 144, iw, "温度", DfcFormat.temperature(d.temperature), WARN);
        row(x, 157, iw, "稳定度", DfcFormat.stabilization(d.stabilization), TXT_DIM);
        row(x, 170, iw, "运行时长", DfcFormat.duration(d.ticks), TXT_DIM);

        textCenter("反应堆温度达到 " + DfcFormat.temperature(ScreenLayout.ONLINE_MIN_TEMP)
                + " 后才进入在线状态", 186, TXT_DIM, fCjk(9));
    }

    /**
     * 顶栏（核心页 / 部件页用）。
     * {@code suffix} 是数据源名后面的小标（"· 部件监视"），传 null 表示不加。
     */
    private void drawHeader(DfcSnapshot d, String suffix) {
        drawHeader(d, suffix, null, null, 0);
    }

    /**
     * 顶栏通用实现。
     *
     * @param suffix      数据源名后面的小标，null = 不加
     * @param title       覆盖左边的标题；null = 用数据源名
     * @param status      覆盖右边的状态词；null = 按"未连接 / 离线 / 已连接"三态自动判断
     * @param statusColor 与 status 配对的状态灯颜色（status 为 null 时忽略）
     */
    private void drawHeader(DfcSnapshot d, String suffix, String title, String status, int statusColor) {
        rect(0, 0, LW, HEADER_H, this.alert ? ALERT_BG : PANEL);
        hline(0, HEADER_H, LW, this.alert ? ALERT_LINE : LINE);

        int nameColor = this.alert ? ALERT_TXT : TXT;
        Font nf = fCjkBold(11);
        String name = title != null ? title : ScreenLayout.sourceName(d);
        text(name, 6, 13, nameColor, nf);

        if (suffix != null && !suffix.isEmpty()) {
            int nx = 6 + textWidthLog(name, nf) + 6;
            text(suffix, nx, 13, this.alert ? ALERT_TXT : CYAN, fCjk(10));
        }

        int color;
        if (status == null) {
            // 三态：未连接（没绑数据源）/ 离线（绑了但温度不到 100°C）/ 已连接。
            // 只分两态的话，"绑了但冷堆"会显示成绿色的"已连接"，
            // 而画面上一片 0 —— 玩家会以为屏坏了。
            if (d == null || !d.linked) {
                status = "未连接";
                color = CRIT;
            } else if (!ScreenLayout.online(d)) {
                status = "离线";
                color = WARN;
            } else {
                status = "已连接";
                color = OK;
            }
        } else {
            color = statusColor;
        }
        Font sf = fCjk(10);
        textRight(status, LW - 8, 13, color, sf);
        rectRight(LW - 8 - textWidthLog(status, sf) - 10, 5, 5, 9, color);

        if (this.alert) {
            drawAlertMark();
        }
    }

    /**
     * 顶栏正中的闪烁 "!!"。
     *
     * 屏幕吊在天花板上，玩家常常是"一抬头扫一眼"，所以告警需要一个
     * **不依赖读数字**的信号：会闪的感叹号。亮/灭各 {@link #BLINK_MS}/2，
     * 用墙钟而不是开机时间轴 —— 告警和"开机多久"无关，任何时候都该在闪。
     * 底色垫一块深红，保证在浅色内容上也看得见。
     */
    private void drawAlertMark() {
        // 闪烁相位取本帧的渲染相位（开机态 = sinceOnMs），不读挂钟：
        // 读挂钟会让"同一时刻导出两帧"永远得到同一张图，闪不闪根本验证不了。
        if (!ScreenLayout.blinkOn(this.framePhaseMs, BLINK_MS)) {
            return;   // 灭的那半周期
        }
        int cx = LW / 2;
        Font f = fCjkBold(14);
        int tw = textWidthLog("!!", f);
        rect(cx - tw / 2 - 5, 2, tw + 10, HEADER_H - 4, 0xFF5A0F0F);
        text("!!", cx - tw / 2, 15, 0xFFFFD2D2, f);
        // 整屏再套一圈闪烁红边：从两三层楼外也能一眼看出"这台在告警"
        glowFrame(LW, LH, 0xFFB32222);
    }

    /**
     * 温度块。四个数据：当前温度（大字）、状态、变化率、熔点上限。
     *
     * 温度数值用 {@link #fitMono} 自适应字号填满栏宽 —— DFC 的温度可能是个位数，
     * 也可能是 "124.7k°C" 这种八字符，固定字号必然在某个区间溢出或留白。
     *
     * 【未连接时要显示"--"而不是 0】0 是个合法读数，一旦拿它去判等级，
     * 0°C 会被判成"正常"（绿）、0% 稳定度会被判成"危急"（红）——
     * 全是假警报，比不显示更糟。
     */
    private void drawTempBlock(int x, int y, int w, DfcSnapshot d) {
        panel(x, y, w, 54);
        text("温度", x + 4, y + 14, themeTitle, fCjk(10));

        if (!d.linked) {
            text("--", x + 4, y + 38, TXT_DIM, fMonoBold(21));
            text("未绑定数据源", x + 4, y + 51, TXT_DIM, fCjk(9));
            return;
        }

        String rate = DfcFormat.rate(d.tempDelta);
        textRight(rate, x + w - 4, y + 14, d.tempDelta > 0.001D ? WARN : CYAN, fMono(9));

        int level = DfcFormat.heatLevel(d.temperature, d.meltingPoint);
        int color = level == 2 ? CRIT : (level == 1 ? WARN : OK);
        String t = DfcFormat.temperature(d.temperature);
        text(t, x + 4, y + 38, color, fitMono(t, w - 8, 21, 12, true));

        // 底行只留"熔点 + 上限"。
        // 【为什么把热工状态词从这一行摘掉】数值统一保留 2 位小数之后，
        // "6000.00°C" 比原来的 "6000°C" 宽了一截，而这一行只有 74 逻辑像素：
        // 左边"熔点"（18）+ 中间数值（44）+ 右边"正常"（18）必然糊成一团
        // （离屏图上就是 "熔点 6000.00°C正常" 挤在一起）。
        // 温度状态本来就已经由大字的颜色表达了，事件日志里也会写"温度危急/温度回落 正常"，
        // 这里再挂一个词属于重复信息，去掉之后这一行终于读得清。
        String mpv = d.meltingPoint > 0 ? DfcFormat.temperature(d.meltingPoint) : "--";
        row(x + 4, y + 51, w - 8, "熔点", mpv, TXT_DIM);
    }

    /**
     * 稳定度块：数值 + 进度条。满标度 10 见 {@link DfcFormat#STAB_FULL_SCALE}。
     *
     * 【量纲】d.stabilization 是作者那边各稳定器累加的无量纲值（不是 0~1），
     * 必须先经 {@link DfcFormat#stabilizationRatio} 归一到满标度，否则永远 999%。
     */
    private void drawStabBlock(int x, int y, int w, DfcSnapshot d) {
        panel(x, y, w, 44);
        text("稳定度", x + 4, y + 14, themeTitle, fCjk(10));

        if (!d.linked) {
            textRight("未绑定", x + w - 4, y + 14, TXT_DIM, fCjk(10));
            text("--", x + 4, y + 35, TXT_DIM, fMonoBold(20));
            bar(x + 4, y + 39, w - 8, 5, 0.0F, TXT_DIM);
            return;
        }

        double ratio = DfcFormat.stabilizationRatio(d.stabilization);
        // 带运行上下文的档位：停堆时 0 稳定度是"没开机"，不是"危急"。
        // 判据见 ScreenLayout.stabLevel —— 与事件日志用的是同一个口子。
        int lv = ScreenLayout.stabLevel(d);
        int color = lv == 2 ? CRIT : (lv == 1 ? WARN : OK);
        textRight(DfcFormat.stabLabel(lv), x + w - 4, y + 14, color, fCjk(10));

        String s = DfcFormat.stabilization(d.stabilization);
        text(s, x + 4, y + 35, color, fitMono(s, w - 8, 20, 12, true));

        bar(x + 4, y + 39, w - 8, 5, (float) ratio, color);
    }

    /**
     * 能量块。四个数据：储能 / 输出 / 输入 / 增益。
     *
     * 【单位一律 SPK，不是 HE】这几个量在这条链路上从头到尾只有 SPK 一种单位
     * （作者自己的 CoreGUI 就是 {@code containedEnergy * 1000 + "SPK"}）。
     * 早期版本走 {@code DfcFormat.energy()} 显示成 HE，量级和游戏本体差 1000 倍。
     * 换算与口径都封在 {@link DfcFormat#spk}/{@link DfcFormat#spkRate} 里。
     *
     * "增益" 是 {@code potentialGain} —— **1 起步的倍率**（圆盘上那根指针的读数），
     * 不是能量，所以既不乘 1000 也不带单位。
     */
    private void drawEnergyBlock(int x, int y, int w, DfcSnapshot d) {
        panel(x, y, w, 55);
        text("能量", x + 4, y + 14, themeTitle, fCjk(10));
        if (d.linked && d.boosted) {
            textRight("增强", x + w - 4, y + 14, ACCENT, fCjk(10));
        }

        int iw = w - 8;
        boolean lk = d.linked;
        row(x + 4, y + 25, iw, "储能", lk ? DfcFormat.spk(d.containedEnergy) : "--",
                lk ? CYAN : TXT_DIM);
        row(x + 4, y + 34, iw, "输出", lk ? DfcFormat.spkRate(d.expellingEnergy) : "--",
                lk ? TXT : TXT_DIM);
        row(x + 4, y + 43, iw, "输入", lk ? DfcFormat.spkRate(d.incomingSpk) : "--",
                lk ? TXT : TXT_DIM);
        row(x + 4, y + 52, iw, "增益", lk ? DfcFormat.gainFactor(d.potentialGain) : "--",
                lk ? (d.gainedEnergy > 0 ? NEEDLE : TXT_DIM) : TXT_DIM);
    }

    /**
     * 中栏：堆芯状态盘。
     *
     * 【一块表盘只归一个量】早期版本把两个**互不相干**的量叠在同一块表盘上：
     * 刻度环的填充 = 堆芯崩溃程度 collapse，而指针 + 右上角金色读数 = 潜在增益 potentialGain。
     * 于是变成"同一根针指着一个数、同一圈环填着另一个数"，操作员读数前得先分辨
     * 自己看的到底是哪一项。需求原话："把潜在增益和崩溃程度分开，不要放一起"。
     *
     * 现在分工：
     *   - **刻度环的填充 + 底行百分比** = 堆芯崩溃程度 collapse（0~1 的熔毁进度，
     *     作者代码 {@code clamp(1 - explosionIn/120, 0, 1)}），36 格逐格点亮，颜色 绿→黄→红；
     *   - **顶行读数** = 稳定度（与左栏"稳定"块同口径、同档位色）；
     *   - **潜在增益**不再出现在这块表盘上 —— 它只留在左栏"能量"块第 4 行。
     *
     * 【指针为什么撤掉】那根针是照抄作者 CoreGUI 的潜在增益指针
     * （{@code angle = min((gain - 1) / 9, client_maxDial) * 360}），
     * 也就是**增益自己的**仪表；只要它还插在崩溃环里，"分开"就等于没做到。
     * {@link DfcFormat#gainRatio} 留着：将来若要给增益单开一格仪表，换算直接可用。
     *
     * 【为什么数字不在盘心】早期版本把 {@code percent(collapsing)} 放在盘心（15 号字），
     * 而盘心那块填充圆半径只有 R*3/5 —— "100.00%" 的宽度是它的两倍多，
     * 于是一串数字横穿同心圆环（放大图里看得非常明显）。
     * 现在盘心只留一个轴点，读数挪到面板顶行/底行，各占一条不重叠的横带。
     *
     * 【纵向分配】面板 23..103（高 80）：
     *   顶行基线 y+12（稳定度）      -> 字底约 y+14
     *   圆盘中心 y+40、半径 R=23     -> 圆盘上下缘 y+17 / y+63
     *   底行基线 y+76（崩溃百分比）  -> 字顶约 y+67
     * 三块之间各留 4px 左右，互不接触。
     */
    private void drawDial(int x, int y, int w, DfcSnapshot d) {
        panel(x, y, w, 80);
        int cx = x + w / 2;
        boolean lk = d.linked;

        float collapse = lk ? (float) Math.max(0.0D, Math.min(1.0D, d.collapsing)) : 0.0F;
        int lv = lk ? DfcFormat.collapseLevel(d.collapsing) : 0;
        int color = lk ? (lv == 2 ? CRIT : (lv == 1 ? WARN : OK)) : 0xFF1E2A34;

        // 【核心一开始崩溃就整盘转黄】盘面是最显眼的位置，操作员一眼扫到的就是它。
        // 崩溃过半后再进一步转红，形成 黄 -> 红 的加剧感（档位判定见 ScreenLayout.alertLevel）。
        //
        // 刻度环跟着一起变色：早期只有盘心变黄、刻度环还按 collapseLevel（>=50% 才降级）
        // 保持青色，于是同一块表盘上"心是黄的、圈是绿的"，读起来自相矛盾。
        int alertLv = ScreenLayout.alertLevel(d);
        if (alertLv > 0) {
            color = alertLv >= 2 ? CRIT : WARN;
        }

        // ---- 顶行：稳定度（这块盘上唯一那不是崩溃的读数） ----
        // 档位走 ScreenLayout.stabLevel（带运行上下文，与左栏"稳定"块同一个口子）——
        // 冷堆的 0 稳定度是"没开机"，不是"危急"。
        text("稳定度", x + 4, y + 12, themeTitle, fCjk(9));
        int slv = lk ? ScreenLayout.stabLevel(d) : -1;
        int sc = slv < 0 ? TXT_DIM : (slv == 2 ? CRIT : (slv == 1 ? WARN : OK));
        String sStab = lk ? DfcFormat.stabilization(d.stabilization) : "--";
        Font sf = fitMono(sStab, w - 8 - textWidthLog("稳定度", fCjk(9)) - 3, 10, 7, false);
        textRight(sStab, x + w - 4, y + 12, sc, sf);

        // ---- 圆盘：环的填充就是"崩溃程度"，与底行那个百分比是**同一个量** ----
        int cy = y + 40;
        int R = Math.min(w, 68) / 2 - 12;

        // 外圈轨道
        ovalStroke(cx - R, cy - R, R * 2, R * 2, themeLine, SS);
        // 36 格刻度画在轨道**内侧**（这样圆盘的外沿就是轨道本身，整体更紧凑）
        g.setStroke(new BasicStroke(SS * 1.0F));
        for (int i = 0; i < 36; i++) {
            // 0 格在**正上方**，顺时针 —— 格数直接就是崩溃程度，读数方向一目了然
            double a = -Math.PI / 2.0D + i * Math.PI / 18.0D;
            int r0 = R - 2;
            int r1 = R - (i % 3 == 0 ? 6 : 3);
            line(cx + (int) (Math.cos(a) * r0), cy + (int) (Math.sin(a) * r0),
                    cx + (int) (Math.cos(a) * r1), cy + (int) (Math.sin(a) * r1),
                    i / 36.0D < collapse ? color : 0xFF1B2C38);
        }
        g.setStroke(new BasicStroke(1.0F));
        // 内圈刻度盘底纹
        ovalStroke(cx - R * 3 / 5, cy - R * 3 / 5, R * 6 / 5, R * 6 / 5, 0xFF163040, SS);
        // 轴心（针撤掉后只留这个点，标明盘心在哪）
        // 颜色刻意用和"内圈底纹"同一个暗色 —— 上一版这里是亮灰(`TXT`)，
        // 没有针的时候那颗亮点就成了盘里唯一的焦点，像一颗掉了针留下的珠子。
        ovalFill(cx - 2, cy - 2, 4, 4, 0xFF163040);

        // ---- 底行：崩溃程度（这块表盘的主读数） ----
        // 颜色直接用上面那个 color（它已经把 alertLevel 算进去了），
        // 不要再单独按 collapseLevel 取一次 —— 否则告警态下会出现"环是红的、数是黄的"，
        // 同一个量两套档位。
        // 标签只能写"崩溃"两个字：中栏只有 70px，"崩溃程度"四个汉字加一个 12 号读数就顶出去了。
        text("崩溃", x + 4, y + 76, themeTitle, fCjk(9));
        String pct = lk ? DfcFormat.percent(d.collapsing) : "--";
        Font pf = fitMono(pct, w - 8 - textWidthLog("崩溃", fCjk(9)) - 3, 12, 9, true);
        textRight(pct, x + w - 4, y + 76, lk ? color : TXT_DIM, pf);
    }

    /**
     * 中栏下：运行时长与结构/稳定器状态。
     *
     * 稳定器那几个值（开关、功率等级、透镜）就是诅咒版
     * {@code MixinTileEntityCoreStabilizer.getQueryData()} 吐出来的东西 ——
     * 玩家在游戏里用控制面板读到的就是这一区，两边对得上才不会一头雾水。
     */
    private void drawRuntimeBlock(int x, int y, int w, DfcSnapshot d) {
        panel(x, y, w, 79);
        text("运行时长", x + 4, y + 14, themeTitle, fCjk(10));

        String dur = DfcFormat.duration(d.ticks);
        Font df = fitMono(dur, w - 8, 15, 11, true);
        g.setFont(df);
        FontMetrics fm = g.getFontMetrics(df);
        g.setColor(new Color(TXT, true));
        g.drawString(dur, (x + w / 2) * SS - fm.stringWidth(dur) / 2, (y + 34) * SS);

        int iw = w - 8;
        row(x + 4, y + 45, iw, "部件", d.componentCount + " 件",
                d.componentCount > 0 ? TXT : WARN);
        row(x + 4, y + 55, iw, "燃料", d.hasCore ? "已装入" : "缺失",
                d.hasCore ? OK : WARN);

        if (d.stabPresent) {
            row(x + 4, y + 65, iw, "稳定器",
                    d.stabActive ? "运行 L" + d.stabLevel : "关闭",
                    d.stabActive ? OK : WARN);
            row(x + 4, y + 75, iw, "透镜", lensText(d),
                    d.lensHealth >= 0.0F && d.lensHealth < 25.0F ? WARN : CYAN);
        } else {
            row(x + 4, y + 65, iw, "稳定器", "未接入", TXT_DIM);
        }
    }

    /**
     * 透镜那一行的值。
     *
     * 透镜类型名是诅咒版的枚举名（PYROCLASTIC / OMEGA...），长的能到 11 个字符，
     * 在 70 逻辑像素宽的栏里必然撞上"透镜"标签 —— 直接截到 6 字符，
     * 保留"能认出是哪种"的最小信息量，健康度用百分比跟在后面。
     */
    private static String lensText(DfcSnapshot d) {
        if (d.lensHealth < 0.0F) {
            return "无";
        }
        String name = d.lensType == null ? "?" : d.lensType;
        if (name.length() > 6) {
            name = name.substring(0, 6);
        }
        return name + " " + DfcFormat.percentRaw(d.lensHealth);
    }

    /** 右栏：事件日志 */
    private void drawEventLog(int x, int y, int w, List<String> events) {
        panel(x, y, w, 165);
        text("事件日志", x + 4, y + 14, this.alert ? ALERT_TXT : CYAN, fCjk(10));
        hline(x + 4, y + 18, w - 8, themeLine);

        int lineH = 11;
        int ty = y + 29;
        int bottom = y + 165 - 5;
        Font f = fCjk(9);
        int textW = w - 9;      // 左右各留一点边距
        int maxRows = (bottom - ty) / lineH + 1;

        // 【先折行，再决定从哪条事件开始画】
        // 一条事件可能占 2~3 行，所以"能显示几条事件"不能再用
        // 行数 = 事件数 来算。这里从**最后一条**往前铺，累计已用行数，
        // 直到超出可视区为止 —— 铺不下的事件直接丢掉（日志本来也就是滚动窗口，
        // 屏幕上显示的是"最近的几条"）。反向铺完之后再正向画，保证
        // 时间顺序仍然是从上到下。
        List<String> rows = new ArrayList<String>();
        List<Integer> colors = new ArrayList<Integer>();
        for (int i = events.size() - 1; i >= 0; i--) {
            String s = events.get(i);
            int color = TXT_DIM;
            if (s.startsWith("##") || s.startsWith("!!")) {
                color = CRIT;
            } else if (s.startsWith("!")) {
                color = WARN;
            } else if (s.startsWith("已连接") || s.startsWith("堆芯上线")) {
                color = OK;
            }
            List<String> parts = wrap(s, textW, f);
            // 一条事件最多占 3 行：占满整屏的日志不是日志，是正文
            if (parts.size() > 3) {
                parts = new ArrayList<String>(parts.subList(0, 3));
                parts.set(2, clip(parts.get(2), textW, f));
            }
            if (rows.size() + parts.size() > maxRows) {
                break;      // 这条塞不下了，更早的也一并放弃
            }
            // 反向插入到列表头，保持"早 -> 晚"的自上而下顺序
            for (int k = parts.size() - 1; k >= 0; k--) {
                rows.add(0, parts.get(k));
                colors.add(0, color);
            }
        }

        for (int i = 0; i < rows.size(); i++) {
            text(rows.get(i), x + 4, ty, colors.get(i), f);
            ty += lineH;
        }
    }

    // ==================== 画面：部件监视（第二页） ====================
    //
    // 布局与第一页共用同一套三栏：左 4..86 / 中 90..160 / 右 164..252，
    // 每栏上下两块（Row1 23..99，Row2 105..188）。六个区块：
    //   发射器 / 接收器 | 燃料 / 燃料喷射器 | 积蓄能量 / 能量爆发
    //
    // 【为什么全部是"合计"】一台反应堆周边可能挂十几台发射器，逐台列出来
    // 在 256x192 上根本放不下，操作员要的也只是"一共几台、总共多少"。

    private static final int PROW1_Y = ROW_TOP;
    private static final int PROW1_H = 76;
    private static final int PROW2_Y = 105;
    private static final int PROW2_H = 83;

    /**
     * 部件页专用的三栏布局。
     *
     * 【为什么不复用核心页的 82/70/88】核心页中栏要放圆盘，只能留 70；
     * 而这一页全是"标签 + 数值"的行，中栏 70 会让 "567.00 kSPK" 这种值被迫缩到
     * 逻辑 6 号字（肉眼已经很吃力）。这一页没有圆盘，三栏可以摊匀，
     * 每栏多出来的 8 个逻辑像素就够数值回到正常字号。
     */
    private static final int CP_L_X = 4;
    private static final int CP_L_W = 78;
    private static final int CP_M_X = 86;
    private static final int CP_M_W = 78;
    private static final int CP_R_X = 168;
    private static final int CP_R_W = 84;

    /** 部件页没有崩溃告警（那是核心页的职责），主题色一律恢复正常 */
    private void drawComponents(DfcSnapshot d) {
        this.alert = false;
        this.themeLine = LINE;
        this.themeTitle = TXT_DIM;

        drawHeader(d, "· 部件监视");

        // 右栏最宽，把需要"一行放下标签 + 名字 + 百分比"的燃料放这儿：
        // 窄栏里流体名会被压得只剩两三个字符（早期版本把 "DFC FUEL" 画成过 "DF.."）。
        drawEmitterBlock(CP_L_X, PROW1_Y, CP_L_W, d);
        drawStoredEnergyBlock(CP_M_X, PROW1_Y, CP_M_W, d);
        drawFuelBlock(CP_R_X, PROW1_Y, CP_R_W, d);

        drawReceiverBlock(CP_L_X, PROW2_Y, CP_L_W, d);
        drawBurstBlock(CP_M_X, PROW2_Y, CP_M_W, d);
        drawInjectorBlock(CP_R_X, PROW2_Y, CP_R_W, d);
    }

    /**
     * 部件页的块标题。
     *
     * 标题一律写**游戏语言文件里的官方中文名**（「DFC发射器」「DFC接收器」
     * 「DFC燃料喷射器」「DFC燃料」「DFC能量爆发」…），跟创造模式物品栏里对得上，
     * 玩家看到屏幕上的名字才知道它说的是哪台机器。
     *
     * 官方名比自造的两字标题长得多（「DFC燃料喷射器」= 3 字母 + 6 汉字），
     * 窄栏（中栏 78 / 右栏 84）里固定 10 号会直接顶出面板 —— 所以这里走
     * {@code fitCjk}：放得下就 10 号，放不下自动缩，绝不溢出。
     */
    private void blockTitle(String s, int x, int y, int w) {
        text(s, x, y, themeTitle, fitCjk(s, w - 8, 10, 7));
    }

    /** 发射器：台数（醒目）+ 工作中台数 / 功率档位之和 / 合计打出 SPK */
    private void drawEmitterBlock(int x, int y, int w, DfcSnapshot d) {
        panel(x, y, w, PROW1_H);
        blockTitle("DFC发射器", x + 4, y + 14, w);

        if (!d.linked) {
            text("--", x + 4, y + 36, TXT_DIM, fMonoBold(19));
            text("未绑定数据源", x + 4, y + 50, TXT_DIM, fCjk(9));
            return;
        }
        String big = d.emitterCount + " 台";
        text(big, x + 4, y + 36, d.emitterCount > 0 ? CYAN : TXT_DIM,
                fitCjk(big, w - 8, 19, 12));
        int iw = w - 8;
        row(x + 4, y + 50, iw, "工作", d.emitterActiveCount + " 台",
                d.emitterActiveCount > 0 ? OK : TXT_DIM);
        row(x + 4, y + 61, iw, "档位", String.valueOf(d.emitterWatts), TXT);
        row(x + 4, y + 72, iw, "输出", DfcFormat.si(d.emitterSpk, "SPK"), TXT);
    }

    /** 接收器：台数 + 合计已接收 / 台均 */
    private void drawReceiverBlock(int x, int y, int w, DfcSnapshot d) {
        panel(x, y, w, PROW2_H);
        blockTitle("DFC接收器", x + 4, y + 14, w);

        if (!d.linked) {
            text("--", x + 4, y + 38, TXT_DIM, fMonoBold(19));
            text("未绑定数据源", x + 4, y + 52, TXT_DIM, fCjk(9));
            return;
        }
        String big = d.receiverCount + " 台";
        text(big, x + 4, y + 38, d.receiverCount > 0 ? CYAN : TXT_DIM,
                fitCjk(big, w - 8, 19, 12));
        int iw = w - 8;
        row(x + 4, y + 54, iw, "合计接收", DfcFormat.si(d.receiverJoules, "J"), TXT);
        double avg = d.receiverCount > 0 ? d.receiverJoules / d.receiverCount : 0.0D;
        row(x + 4, y + 66, iw, "台均", DfcFormat.si(avg, "J"), TXT_DIM);
        // 第三行放"没有接收器"这件事本身 —— 比硬塞一个无从解释的比率有用得多
        row(x + 4, y + 78, iw, "状态",
                d.receiverCount > 0 ? "在线" : "无", d.receiverCount > 0 ? OK : TXT_DIM);
    }

    /** 燃料：核心自带的两个燃料罐（类型 + 液位条 + 百分比） */
    private void drawFuelBlock(int x, int y, int w, DfcSnapshot d) {
        panel(x, y, w, PROW1_H);
        // 标题里带"罐"，行标签就能只用 A / B 一个字母 ——
        // 一个汉字省下来的宽度足够让 "DFC FUEL" 这种长流体名完整显示。
        // 名字用官方的那条（`trait.dfcFuel` = 「DFC燃料」），加个"罐"字说明这里画的是罐。
        blockTitle("DFC燃料罐", x + 4, y + 14, w);

        boolean lk = d.linked;
        drawTankRow(x + 4, y + 30, w - 8, "A", lk ? d.fuelTypeA : "--",
                lk ? d.fuelRatioA : 0.0D, lk);
        drawTankRow(x + 4, y + 54, w - 8, "B", lk ? d.fuelTypeB : "--",
                lk ? d.fuelRatioB : 0.0D, lk);
    }

    /**
     * 一行燃料罐：标签 + 流体名 + 百分比 + 液位条。
     * 流体名先缩字号、实在塞不下才裁切 —— DFC 的燃料名可能是 "DFC FUEL" 这种，
     * 固定字号硬裁会变成 "DF.."，等于没显示。
     */
    private void drawTankRow(int x, int y, int w, String label, String name, double ratio, boolean lk) {
        Font lf = fCjk(9);
        text(label, x, y, themeTitle, lf);

        Font pf = fMono(9);
        // 未连接时百分比也要显示 "--"：0% 是个合法读数，
        // 单独摆在那里会被读成"罐是空的"，而真相是"根本没接上数据源"。
        String pct = lk ? DfcFormat.percent(ratio) : "--";
        int pctW = textWidthLog(pct, pf);
        textRight(pct, x + w, y, lk ? TXT : TXT_DIM, pf);

        int nameX = x + textWidthLog(label, lf) + 5;
        int nameMax = Math.max(6, (x + w) - pctW - 5 - nameX);
        String nm = name == null ? "--" : name;
        Font nf = fitMono(nm, nameMax, 9, 6, false);
        if (g.getFontMetrics(nf).stringWidth(nm) > nameMax * SS) {
            nm = clip(nm, nameMax, nf);
        }
        text(nm, nameX, y, lk ? CYAN : TXT_DIM, nf);

        // 液位条：快见底（<15%）转黄，提醒该加燃料了
        int barColor = !lk ? TXT_DIM : (ratio < 0.15D ? WARN : CYAN);
        bar(x, y + 4, w, 6, lk ? (float) ratio : 0.0F, barColor);
    }

    /** 燃料喷射器：台数 + 合计储液 / 台均 */
    private void drawInjectorBlock(int x, int y, int w, DfcSnapshot d) {
        panel(x, y, w, PROW2_H);
        blockTitle("DFC燃料喷射器", x + 4, y + 14, w);

        if (!d.linked) {
            text("--", x + 4, y + 38, TXT_DIM, fMonoBold(19));
            text("未绑定数据源", x + 4, y + 52, TXT_DIM, fCjk(9));
            return;
        }
        String big = d.injectorCount + " 台";
        text(big, x + 4, y + 38, d.injectorCount > 0 ? CYAN : TXT_DIM,
                fitCjk(big, w - 8, 19, 12));
        int iw = w - 8;
        row(x + 4, y + 54, iw, "储液", DfcFormat.si(d.injectorFuel, "mB"), TXT);
        double avg = d.injectorCount > 0 ? d.injectorFuel / d.injectorCount : 0.0D;
        row(x + 4, y + 66, iw, "台均", DfcFormat.si(avg, "mB"), TXT_DIM);
        row(x + 4, y + 78, iw, "连接率",
                d.emitterCount + d.receiverCount + d.injectorCount + " 件", TXT_DIM);
    }

    /**
     * 核心能量：当前值 + 相对满刻度的进度条（单位 SPK，见 DfcFormat.spk）。
     *
     * 标题用「DFC能量」：这一项在游戏语言文件里**没有**现成的名字
     * （作者 CoreGUI 直接写英文 "Contained Energy"），所以取官方缩写 DFC + 一个中性词，
     * 和旁边那块「DFC能量爆发」成对。
     */
    private void drawStoredEnergyBlock(int x, int y, int w, DfcSnapshot d) {
        panel(x, y, w, PROW1_H);
        blockTitle("DFC能量", x + 4, y + 14, w);

        if (!d.linked) {
            text("--", x + 4, y + 38, TXT_DIM, fMonoBold(19));
            text("未绑定数据源", x + 4, y + 52, TXT_DIM, fCjk(9));
            return;
        }
        String v = DfcFormat.spk(d.containedEnergy);
        text(v, x + 4, y + 38, CYAN, fitMono(v, w - 8, 19, 12, true));

        // 满刻度照抄作者 CoreGUI 的那根条：containedEnergy / 100000
        // （不用 getDFCMaxEnergy 的原因见 DfcFormat.CORE_ENERGY_BAR_SCALE 的注释）
        double ratio = d.containedEnergy / DfcFormat.CORE_ENERGY_BAR_SCALE;
        bar(x + 4, y + 44, w - 8, 7, (float) Math.max(0.0D, Math.min(1.0D, ratio)), CYAN);

        row(x + 4, y + 62, w - 8, "满格", DfcFormat.spk(DfcFormat.CORE_ENERGY_BAR_SCALE), TXT_DIM);
        row(x + 4, y + 72, w - 8, "爆堆", DfcFormat.spk(DfcFormat.CORE_FAILSAFE),
                d.containedEnergy >= DfcFormat.CORE_FAILSAFE ? CRIT : TXT_DIM);
    }

    /**
     * DFC能量爆发：最近 1 秒吐给接收器的能量之和（作者 CoreGUI 的 "Expelling Energy" 同口径）。
     *
     * 标题「DFC能量爆发」不是自造词 —— 它是整合包语言文件里的官方名字
     * （{@code subtitles.block.dfc.energyburst=DFC能量爆发}，就是核心放能那一声的字幕）。
     */
    private void drawBurstBlock(int x, int y, int w, DfcSnapshot d) {
        panel(x, y, w, PROW2_H);
        blockTitle("DFC能量爆发", x + 4, y + 14, w);

        if (!d.linked) {
            text("--", x + 4, y + 40, TXT_DIM, fMonoBold(19));
            text("未绑定数据源", x + 4, y + 54, TXT_DIM, fCjk(9));
            return;
        }
        String v = DfcFormat.spkRate(d.expellingEnergy);
        text(v, x + 4, y + 40, d.expellingEnergy > 0.0D ? WARN : TXT_DIM,
                fitMono(v, w - 8, 18, 11, true));

        // 爆发强度条：以"一直在满刻度的输出"为满格，给个直观量级
        double ratio = d.expellingEnergy / DfcFormat.CORE_ENERGY_BAR_SCALE;
        bar(x + 4, y + 46, w - 8, 7,
                (float) Math.max(0.0D, Math.min(1.0D, ratio)), WARN);

        // 标签只写"台均"：中文字族里 "台均SPK" 的宽度会把数值挤到只能裁切，
        // 单位已经在数值里了（"3.40 MSPK/s"）。
        double per = d.receiverCount > 0 ? d.expellingEnergy / d.receiverCount : 0.0D;
        row(x + 4, y + 64, w - 8, "台均",
                d.receiverCount > 0 ? DfcFormat.spkRate(per) : "--", TXT_DIM);
        // 输入（增强打进来的那一份）—— 与核心页同口径，两页对得上
        row(x + 4, y + 74, w - 8, "输入",
                d.incomingSpk > 0.0D ? DfcFormat.spkRate(d.incomingSpk) : "--",
                d.incomingSpk > 0.0D ? ACCENT : TXT_DIM);
    }

    // ==================== 画面：自定义数据列表（第三页） ====================
    //
    // 需求："一个自定义显示屏，最多可以显示 8 个数据 …… 中有列表显示 8 个数据，可以删除数据"。
    // 删除在 GUI 里做（那里才有鼠标），屏幕上只负责"把 8 条读出来"。
    //
    // 【为什么是 8 行单列，而不是 2 列 x 4 行】每行要放下"设备名 · 数据名"和
    // "当前值 / 满值"两段信息。2 列时每列只有 124 逻辑像素，"储液罐 · 柴油" 后面
    // 就再也塞不下 "12.34 kmB / 24.00 kmB"；单列 248 像素宽，两边都放得下，
    // 而且从上往下一行一行扫，读起来就是一张清单。

    /** 自定义页的行高（8 行 × 20 = 160，正好落在 23..183） */
    private static final int CROW_TOP = ROW_TOP;
    private static final int CROW_H = 20;

    private void drawCustom(DfcSnapshot d) {
        // 自定义页不参与崩溃告警：它盯的是储罐和电池，跟堆芯坍缩没有关系。
        // 主题色复位成正常，免得从核心页切页过来时残留红色。
        this.alert = false;
        this.themeLine = LINE;
        this.themeTitle = TXT_DIM;

        List<DfcWatch> list = d.watches;
        // 【顶栏不能沿用"已连接/未连接"】那是"有没有绑上反应堆"的意思。
        // 自定义屏盯的是外部设备，跟反应堆没关系 —— 一块只接了储罐的屏
        // 顶着一行红色"未连接"会让人以为它坏了。这一页改报"已用几个槽"。
        int used = list.size();
        drawHeader(d, null, "数据监视", used + " / " + DfcWatch.MAX + " 条", used > 0 ? OK : WARN);

        // 【一条都没有时整屏只画引导文案】照旧先铺 8 行"空"的话，
        // 正中间那三行会和提示文字叠在一起（离屏图上就是"空 尚未接入数据 空"糊成一团）。
        if (used == 0) {
            textCenter("尚未接入数据", 88, TXT_DIM, fCjk(15));
            // 【文案跟着交互改】原来写的是"蹲下右键本屏接入"，
            // 现在接入动作搬进了 GUI（把遥控器放进槽位点「添加」），
            // 屏幕上再写旧手法会把人引到一条已经不好用的路上。
            textCenter("右键本屏打开面板", 112, TXT_DIM, fCjk(9));
            textCenter("把 DFC 遥控器放入槽位后点「添加」", 126, TXT_DIM, fCjk(9));
            return;
        }

        Font idxF = fMono(9);
        Font nameF = fCjk(9);

        for (int i = 0; i < DfcWatch.MAX; i++) {
            int rowY = CROW_TOP + i * CROW_H;
            // 隔行一条极淡的底：8 行挤在一起，没有斑马纹很容易看串行
            if ((i & 1) == 0) {
                rect(4, rowY - 2, LW - 8, CROW_H - 2, 0x14FFFFFF);
            }
            text(String.valueOf(i + 1), 6, rowY + 11, 0xFF3E586B, idxF);

            if (i >= used) {
                text("空", 18, rowY + 11, 0xFF2E4456, nameF);
                continue;
            }

            DfcWatch w = list.get(i);
            String value = w.alive ? w.value : "离线";
            // 【含汉字的值必须走中文字族】"离线" 用等宽英文字体画出来是一排方块。
            Font vf = hasCjk(value) ? fitCjk(value, 110, 9, 7) : fitMono(value, 110, 9, 7, false);
            int vw = textWidthLog(value, vf);
            int leftMax = Math.max(20, (LW - 6 - vw - 6) - 18);
            String left = w.device + " · " + (w.name == null || w.name.isEmpty() ? "--" : w.name);
            text(clip(left, leftMax, nameF), 18, rowY + 11, w.alive ? TXT : TXT_DIM, nameF);
            textRight(value, LW - 6, rowY + 11, w.alive ? CYAN : CRIT, vf);

            if (w.alive && w.ratio >= 0.0D) {
                bar(18, rowY + 14, LW - 24, 3, (float) w.ratio, barColor(w.ratio));
            }
        }
    }

    /** 进度条配色：快见底（<15%）转黄，其余青色 */
    private static int barColor(double ratio) {
        return ratio < 0.15D ? WARN : CYAN;
    }

    // ==================== 密码盘面板（第四种画法） ====================

    /**
     * 画密码盘。**和 DFC 三块屏共用同一张画布与纹理管线**，
     * 所以这里只需要关心"画什么"，不用再碰 GL。
     *
     * 画布 256×192，布局常量全部来自 {@link com.jiezhimod.keypad.KeypadLayout} ——
     * 渲染和"右键点到哪个键"用的是同一份数字，不会出现"看着按 5、实际输 6"。
     *
     * @param password 正确密码（画在便签纸上，玩家照着输）
     * @param buffer   已输入的数字串
     * @param length   密码位数
     * @param locked   是否已锁定
     * @param accepted 是否已通过验证
     * @param rejecting 刚输错（键盘描边闪红）
     */
    public void renderKeypad(String password, String buffer, int length, boolean locked,
                             boolean accepted, boolean rejecting, long nowMs) {
        long step = rejecting ? 120L : 250L;
        long bucket = nowMs / step;
        if (bucket == this.lastBucket && !this.pixelDirty) {
            return;
        }
        this.lastBucket = bucket;
        this.framePhaseMs = nowMs;
        this.alert = false;
        this.themeLine = LINE;

        g.setColor(new Color(BG, true));
        g.fillRect(0, 0, TEX_W, TEX_H);

        drawKeypadFace(password, buffer, length, locked, accepted, rejecting);

        drawScanlines();
        image.getRGB(0, 0, TEX_W, TEX_H, pixels, 0, TEX_W);
        pixelDirty = true;
    }

    private void drawKeypadFace(String password, String buffer, int length, boolean locked,
                                boolean accepted, boolean rejecting) {
        // ---------- 顶部：标题 + 状态 ----------
        text("密码锁", 9, 18, 0xFFE6ECF2, fCjkBold(12));
        String state;
        int stateColor;
        if (locked) {
            state = "已锁定";
            stateColor = 0xFFE0736B;
        } else if (accepted) {
            state = "已通过";
            stateColor = 0xFF52E08A;
        } else {
            state = "待输入";
            stateColor = 0xFF7FA8C8;
        }
        textRight(state, 247, 18, stateColor, fCjk(10));
        hline(9, 24, 238, 0xFF243040);

        // ---------- 已输入的位（6 个格子） ----------
        int boxW = 24;
        int gap = 4;
        for (int i = 0; i < length; i++) {
            int bx = 9 + i * (boxW + gap);
            int by = 31;
            rect(bx, by, boxW, 20, 0xFF0E1620);
            rect(bx, by, boxW, 1, 0xFF2A3A4A);
            rect(bx, by + 19, boxW, 1, 0xFF2A3A4A);
            if (i < buffer.length()) {
                String d = String.valueOf(buffer.charAt(i));
                textCenterIn(d, bx, boxW, by + 15, 0xFFEAF2FA, fMonoBold(13));
            } else {
                // 空位画一条短横，比留白更能看出"还有几位没输"
                rect(bx + 8, by + 14, boxW - 16, 2, 0xFF22303E);
            }
        }

        // ---------- 键盘 ----------
        for (int i = 0; i < KeypadLayout.KEY_COUNT; i++) {
            int kx = KeypadLayout.keyX(i);
            int ky = KeypadLayout.keyY(i);
            int kw = KeypadLayout.KEY_W;
            int kh = KeypadLayout.KEY_H;

            int base = 0xFF16202B;
            int edge = rejecting ? 0xFFD05050 : 0xFF2E3F52;
            int fg = 0xFFE8F0F8;
            if (locked) {
                base = 0xFF1A1418;
                edge = 0xFF4A2A2E;
                fg = 0xFF6A5A5E;
            } else if (i == KeypadLayout.KEY_ENTER) {
                base = 0xFF1B3324;
                edge = 0xFF3E7A55;
                fg = 0xFF9BE8B6;
            } else if (i == KeypadLayout.KEY_CLEAR) {
                base = 0xFF2A1D18;
                edge = 0xFF6A4A32;
                fg = 0xFFE0B080;
            }

            rect(kx, ky, kw, kh, base);
            rect(kx, ky, kw, 1, edge);
            rect(kx, ky + kh - 1, kw, 1, edge);
            rect(kx, ky, 1, kh, edge);
            rect(kx + kw - 1, ky, 1, kh, edge);

            String label = KeypadLayout.label(i);
            int ty = ky + kh / 2 + 5;
            if (label.length() > 1) {
                textCenterIn(label, kx, kw, ty, fg, fCjk(10));
            } else {
                textCenterIn(label, kx, kw, ty, fg, fMonoBold(15));
            }
        }

        // ---------- 便签纸：把密码写给玩家看 ----------
        drawNote(password);

        if (locked) {
            textCenterIn("键盘已锁定", KeypadLayout.KEY_X, KeypadLayout.padRight() - KeypadLayout.KEY_X,
                    KeypadLayout.padBottom() + 12, 0xFFE0736B, fCjk(10));
        }
    }

    /**
     * 便签纸。**故意画成微微倾斜的** —— 一块贴在台面上的纸，
     * 正着摆反而像面板的一部分，倾斜一点才一眼看出"这是张便签"。
     */
    private void drawNote(String password) {
        int nx = KeypadLayout.NOTE_X;
        int ny = KeypadLayout.NOTE_Y;
        int nw = KeypadLayout.NOTE_W;
        int nh = KeypadLayout.NOTE_H;

        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
        java.awt.geom.AffineTransform old = g2.getTransform();
        double cx = (nx + nw / 2.0D) * SS;
        double cy = (ny + nh / 2.0D) * SS;
        g2.rotate(Math.toRadians(-5.0D), cx, cy);

        // 纸：暖白底 + 一点阴影，边缘压一条浅色
        rect(nx + 2, ny + 2, nw, nh, 0x33000000);
        rect(nx, ny, nw, nh, 0xFFF3E7CC);
        rect(nx, ny, nw, 1, 0xFFFBFAF2);
        // 顶部一条浅红，像便签本上的胶条
        rect(nx, ny, nw, 6, 0xFFE8A9A0);

        text("开发者代码", nx + 5, ny + 18, 0xFF6A5A48, fCjk(8));
        text(password == null || password.isEmpty() ? "------" : password,
                nx + 5, ny + 40, 0xFF2B2622, fMonoBold(13));
        hline(nx + 5, ny + 44, nw - 10, 0xFFC9BBA0);

        g2.setTransform(old);
    }

    // ==================== 绘制原语（逻辑坐标 -> 物理坐标） ====================

    private void rect(int x, int y, int w, int h, int argb) {
        g.setColor(new Color(argb, true));
        g.fillRect(x * SS, y * SS, w * SS, h * SS);
    }

    /** 以逻辑右边界对齐的矩形 */
    private void rectRight(int rightX, int y, int w, int h, int argb) {
        rect(rightX - w, y, w, h, argb);
    }

    /**
     * 一个带底与 1px 描边的面板块。
     * 描边颜色取 {@link #themeLine} —— 崩溃告警时整块屏的面板边框一起转暗红，
     * 这是"面板主题色转红"的主要体现。
     */
    private void panel(int x, int y, int w, int h) {
        rect(x, y, w, h, PANEL);
        rect(x, y, w, 1, themeLine);
        rect(x, y + h - 1, w, 1, themeLine);
        rect(x, y, 1, h, themeLine);
        rect(x + w - 1, y, 1, h, themeLine);
    }

    private void hline(int x, int y, int w, int argb) {
        rect(x, y, w, 1, argb);
    }

    private void line(int x0, int y0, int x1, int y1, int argb) {
        g.setColor(new Color(argb, true));
        g.drawLine(x0 * SS, y0 * SS, x1 * SS, y1 * SS);
    }

    private void ovalFill(int x, int y, int w, int h, int argb) {
        g.setColor(new Color(argb, true));
        g.fillOval(x * SS, y * SS, w * SS, h * SS);
    }

    private void ovalStroke(int x, int y, int w, int h, int argb, int thickness) {
        g.setColor(new Color(argb, true));
        g.setStroke(new BasicStroke((float) thickness));
        g.drawOval(x * SS, y * SS, w * SS, h * SS);
        g.setStroke(new BasicStroke(1.0F));
    }

    private void oval(int x, int y, int w, int h, int argb) {
        ovalFill(x, y, w, h, argb);
    }

    /** 进度条：底槽 + 按比例填充 */
    /** 进度条：底槽 + 按比例的填充。ratio > 0 时至少画 1 像素 —— 见下方说明 */
    private void bar(int x, int y, int w, int h, float ratio, int color) {
        rect(x, y, w, h, 0xFF12202B);
        int fw = Math.round(w * Math.max(0.0F, Math.min(1.0F, ratio)));
        if (fw == 0 && ratio > 0.0F) {
            // 【为什么留 1 像素的地板】DFC 的"能量爆发"相对容量只有千分之几，
            // 取整后是 0 像素 —— 一条完全空着的槽在屏幕上和"没画"分不清，
            // 操作员会以为坏了。留 1 像素是"确实有流量"的最低可读信号。
            fw = 1;
        }
        if (fw > 0) {
            rect(x, y, fw, h, color);
        }
    }

    /**
     * 一行"中文标签 …… 数值"，数值右对齐到 x+width。
     *
     * 值的字体按内容选：含汉字走中文族（"已装入"），纯 ASCII 走等宽族（"1.50 kHE/t"）；
     * 再用 {@link #fitMono} 压到剩余宽度内。**缩到最小字号仍塞不下就裁切** ——
     * 早期只缩不裁，遇到过 "合计储液" + "4.00 kmB" 挤成一坨"合计储液4.00 kmB"，
     * 标签和数值糊在一起两个都读不出来。
     */
    private void row(int x, int y, int width, String label, String value, int valueColor) {
        Font lf = fCjk(9);
        text(label, x, y, TXT_DIM, lf);
        int avail = Math.max(8, width - textWidthLog(label, lf) - 6);
        String vs = value == null ? "--" : value;
        Font vf = hasCjk(vs) ? fitCjk(vs, avail, 9, 7) : fitMono(vs, avail, 9, 6, false);
        if (g.getFontMetrics(vf).stringWidth(vs) > avail * SS) {
            vs = clip(vs, avail, vf);
        }
        textRight(vs, x + width, y, valueColor, vf);
    }

    /** 有没有汉字（用来决定用中文族还是等宽族） */
    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x2E80) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 maxSize 往下找一个能塞进 maxLogicalW 的字号。
     * 温度/百分比这类数值长度差异很大（"9°C" ↔ "124.7k°C"），
     * 固定字号必然在某一端溢出或留一大片空，自适应才是"数值占满整行"的正解。
     */
    private Font fitMono(String s, int maxLogicalW, int maxSize, int minSize, boolean bold) {
        for (int sz = maxSize; sz > minSize; sz--) {
            Font f = bold ? fMonoBold(sz) : fMono(sz);
            if (g.getFontMetrics(f).stringWidth(s) <= maxLogicalW * SS) {
                return f;
            }
        }
        return bold ? fMonoBold(minSize) : fMono(minSize);
    }

    /**
     * 同 {@link #fitMono}，但走中文字族 —— 用于 "12 台" 这种含汉字的短串。
     * （等宽英文字体不含汉字，拿它画会得到一排方块。）
     */
    private Font fitCjk(String s, int maxLogicalW, int maxSize, int minSize) {
        for (int sz = maxSize; sz > minSize; sz--) {
            Font f = fCjk(sz);
            if (g.getFontMetrics(f).stringWidth(s) <= maxLogicalW * SS) {
                return f;
            }
        }
        return fCjk(minSize);
    }

    private void text(String s, int x, int y, int argb, Font f) {
        g.setFont(f);
        g.setColor(new Color(argb, true));
        g.drawString(s, x * SS, y * SS);
    }

    /** 右对齐到逻辑 x = rightX */
    private void textRight(String s, int rightX, int y, int argb, Font f) {
        g.setFont(f);
        int pw = g.getFontMetrics(f).stringWidth(s);
        g.setColor(new Color(argb, true));
        g.drawString(s, rightX * SS - pw, y * SS);
    }

    private void textCenter(String s, int cy, int argb, Font f) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics(f);
        int w = fm.stringWidth(s);
        g.setColor(new Color(argb, true));
        g.drawString(s, (LW / 2) * SS - w / 2, cy * SS);
    }

    /** 在以 x 起、宽 w 的区域内水平居中 */
    private void textCenterIn(String s, int x, int w, int baselineY, int argb, Font f) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics(f);
        int sw = fm.stringWidth(s);
        g.setColor(new Color(argb, true));
        g.drawString(s, (x + w / 2) * SS - sw / 2, baselineY * SS);
    }

    /** 字符串的逻辑宽度（向上取整，供布局判断用） */
    private int textWidthLog(String s, Font f) {
        return (int) Math.ceil(g.getFontMetrics(f).stringWidth(s) / (double) SS);
    }

    /** 超宽就截断并加省略号（显示器不能让文字溢出面板） */
    private String clip(String s, int maxLogicalWidth, Font f) {
        FontMetrics fm = g.getFontMetrics(f);
        if (fm.stringWidth(s) <= maxLogicalWidth * SS) {
            return s;
        }
        String ell = "..";
        int i = s.length();
        while (i > 0 && fm.stringWidth(s.substring(0, i) + ell) > maxLogicalWidth * SS) {
            i--;
        }
        return s.substring(0, Math.max(0, i)) + ell;
    }

    /**
     * 按逻辑宽度把一行文字**折成多行**（不截断）。
     *
     * 【为什么要折行而不是截断】事件日志里像
     * "消耗速率 5.00 mB/t（正常）" 这种句子在 88 逻辑像素宽的栏里一行放不下，
     * 早期直接 {@link #clip} 成 "消耗速率 5.00 m.." —— 后半截正是关键信息
     * （是"正常"还是"偏高"），被砍掉了等于没写。日志本来就不长，
     * 折成两行反而读得全。
     *
     * 【折行规则】
     *   - 逐个字符累加，超宽就在**前一个可断点**处切；
     *   - 断点优先取空格（英文/数字之间），中文没有空格就按字符断（CJK 本来就逐字可断）；
     *   - 单个字符就超过整行宽（极端窄栏）时强制放一个字符，保证不死循环。
     *
     * 【为什么要按逻辑宽度算】绘制坐标都乘了 SS，用物理像素算出来的折行点
     * 在 SS=3 下会偏到三倍宽度去 —— 与字号必须走逻辑量纲是同一个道理。
     */
    private List<String> wrap(String s, int maxLogicalWidth, Font f) {
        List<String> lines = new ArrayList<String>();
        if (s == null || s.isEmpty()) {
            lines.add("");
            return lines;
        }
        FontMetrics fm = g.getFontMetrics(f);
        int maxPx = Math.max(1, maxLogicalWidth * SS);
        int start = 0;
        while (start < s.length()) {
            int end = start;
            int lastBreak = -1;
            // 尽量往后吃字符，直到超宽；记住最后一个可断点（空格之后）
            while (end < s.length()) {
                int w = fm.stringWidth(s.substring(start, end + 1));
                if (w > maxPx) {
                    break;
                }
                if (s.charAt(end) == ' ') {
                    lastBreak = end;
                }
                end++;
            }
            if (end >= s.length()) {
                lines.add(s.substring(start));
                break;
            }
            int cut;
            if (lastBreak > start) {
                cut = lastBreak;          // 在空格处断，空格本身留给下一行前导（下面 trim）
            } else if (end > start) {
                cut = end;                // 中文没有空格，逐字断
            } else {
                cut = start + 1;          // 单字就超宽：强制吃一个，避免死循环
            }
            lines.add(s.substring(start, cut));
            start = cut;
            // 跳过被断在行首的空格
            while (start < s.length() && s.charAt(start) == ' ') {
                start++;
            }
        }
        return lines;
    }

    /**
     * 扫描线：液晶面板的横向暗纹。
     * 每 3 逻辑像素一条 —— 早期每 2 像素一条且不透明度偏高，
     * 在 3 倍超采样下会把小字啃掉一半笔画，看起来就是"糊"。
     */
    private void drawScanlines() {
        g.setColor(new Color(0, 0, 0, 16));
        for (int y = 0; y < TEX_H; y += 3 * SS) {
            g.fillRect(0, y, TEX_W, SS);
        }
    }
}
