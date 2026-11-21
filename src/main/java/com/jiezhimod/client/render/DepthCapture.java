package com.jiezhimod.client.render;

import com.jiezhimod.JiezhiMod;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * 屏幕空间聚光灯（SSS）：把主 FBO 的<b>深度</b>（阶段 0）和<b>颜色</b>（阶段 2）拷进自己的纹理。
 *
 * <h3>先说清楚一件事：原版主 FBO 没有深度纹理</h3>
 * 对着 {@code forge-...-mapped_snapshot_20171003-1.12.jar} 里的
 * {@code net.minecraft.client.shader.Framebuffer#createFramebuffer} 反编译数过，
 * 它只用颜色纹理 + <b>深度 renderbuffer</b>：
 * <pre>
 *   depthBuffer = OpenGlHelper.glGenRenderbuffers()
 *   glRenderbufferStorage(GL_RENDERBUFFER,
 *         33190 = GL_DEPTH_COMPONENT24      （未开 stencil）
 *      或 35056 = GL_DEPTH24_STENCIL8        （enableStencil() 之后）)
 *   glFramebufferRenderbuffer(GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthBuffer)
 * </pre>
 * <b>renderbuffer 不能被采样</b>，所以「直接拿 mc.getFramebuffer() 的深度纹理」这条路上
 * 只有两个选择：
 * <ol>
 *   <li><b>blit 拷一份</b>（本类的做法）：自建一个只有深度附件的 FBO，每帧
 *       {@code glBlitFramebuffer(GL_DEPTH_BUFFER_BIT)} 把原版 FBO 的深度搬过去。
 *       完全不动原版 FBO，出错也只坏自己这一张图。</li>
 *   <li><b>把自己的深度纹理挂到原版 FBO 上</b>（替换 renderbuffer，Distant Horizons 走的路）：
 *       零拷贝、每帧省一次 blit，但要负责「分辨率变化 / stencil 开关 / 退出存档时把原
 *       renderbuffer 还回去」，一次漏还原就是原版深度全废。阶段 0 不冒这个险。</li>
 * </ol>
 *
 * <h3>格式必须跟源一致，否则 blit 直接 INVALID_OPERATION</h3>
 * {@code glBlitFramebuffer} 拷深度时，读、写两侧的深度格式必须"兼容"：
 * 源是 {@code DEPTH24_STENCIL8}（带 stencil）而目标是纯 {@code DEPTH_COMPONENT24}
 * 就会报错、深度纹理里一片空。所以这里<b>镜像</b>源的格式：
 * {@code src.isStencilEnabled() ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24}。
 * 注意 stencil 并不只在光影下才会开 —— 原版实体描边（旁观者 / 发光实体）也会
 * {@code enableStencil()}，并把整块 FBO 按新格式重建一次。所以这里每一帧都对
 * "FBO id + 尺寸 + stencil 标志"做签名比对，变了就重建。
 *
 * <h3>阶段 2：为什么连颜色也拷</h3>
 * 聚光灯的最终结果必须是「原画面 + 光」，所以着色器要在同一个像素上同时拿到
 * 深度和场景色。原版主 FBO 的颜色<b>是</b>一张纹理，但它贴在 {@code framebufferTexture}
 * 上，而我们在同一帧里要用两个采样器（深度 + 场景），只能各占一个纹理单元 —— 把
 * 主 FBO 的颜色纹理直接绑到 unit1 在原理上也可行，但那样就等于依赖"别的 mod 没在这
 * 之后往主 FBO 上又画东西"，且 {@code glDrawBuffer} 状态属于原版，我们只读不改更安全。
 *
 * <p>颜色格式必须与源严格一致才能 blit：反编译核对过
 * {@code Framebuffer#createFramebuffer} 用的是
 * {@code internalFormat=32856(GL_RGBA8), format=6408(GL_RGBA), type=5121(GL_UNSIGNED_BYTE)}，
 * 所以本类也照这个建。颜色和深度<b>一次 blit 同时拷</b>（一个 {@code glBlitFramebuffer} 调用）。</p>
 *
 * <p>万一驱动不认"一次拷两种附件"，会自动退回只拷深度并把 {@link #colorCopied()} 置 false，
 * 调用方据此改用纯叠加混合（加法混合）出图 —— 画面依旧有光，只是没法采样场景色。</p>
 */
public final class DepthCapture {

    /** 目标 FBO；深度附件必有，颜色附件正常情况下也有 */
    private int fbo = -1;
    /** 深度纹理，拷完之后可以直接当 sampler2D 用 */
    private int depthTexture = -1;
    /** 场景颜色副本（GL_RGBA8），给着色器做"原画面 + 光" */
    private int colorTexture = -1;
    /** 本帧颜色是否真的拷到了（false = 只能用加法混合兜底） */
    private boolean colorCopied;

    private int width = -1;
    private int height = -1;
    /** 源签名：主 FBO 一旦换过（分辨率 / 全屏切换 / 资源重载 / stencil 开关）就重建 */
    private int srcFboId = -1;
    private boolean srcStencil;

    /** null = 还没探测过；老显卡上 glBlitFramebuffer 可能根本不存在 */
    private static Boolean blitSupported;

    /** blit 的错误只报一次，避免每帧刷屏（日志被冲垮时真正的第一行反而看不见） */
    private boolean blitErrorLogged;
    /** "颜色合并 blit 不可用"只报一次 */
    private boolean colorFallbackLogged;

    public int depthTexture() {
        return depthTexture;
    }

    /** 场景颜色副本；调用方必须先确认 {@link #colorCopied()} 为 true 再采样 */
    public int colorTexture() {
        return colorTexture;
    }

    public boolean colorCopied() {
        return colorCopied;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * 把主 FBO 当前帧的<b>深度（+ 颜色）</b>拷进本类持有的纹理。
     *
     * <p><b>调用时机很关键</b>：必须在世界已经画完、且没有任何东西再清深度之前
     * （{@code RenderWorldLastEvent} 就是那个点）。晚了就是空图。</p>
     *
     * @return 成功拷到深度才返回 true；调用方拿 false 时应当整段跳过，不要画出半成品
     */
    public boolean capture(Framebuffer src) {
        if (!canBlit() || !OpenGlHelper.framebufferSupported) {
            return false;
        }
        if (src == null || src.framebufferObject <= 0
                || src.framebufferTextureWidth <= 0 || src.framebufferTextureHeight <= 0) {
            return false;
        }
        if (!ensureTarget(src)) {
            return false;
        }

        int glError;
        try {
            // blit 只认"分别绑定读/写"，不能绑一个 GL_FRAMEBUFFER 了事
            OpenGlHelper.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, src.framebufferObject);
            OpenGlHelper.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            // 先把此前攒下的错误码清干净，否则下面读到的可能是原版遗留的旧错误
            drainGlError();

            int mask = GL11.GL_DEPTH_BUFFER_BIT;
            if (colorTexture > 0) {
                mask |= GL11.GL_COLOR_BUFFER_BIT;
            }
            GL30.glBlitFramebuffer(
                    0, 0, width, height,
                    0, 0, width, height,
                    mask,
                    GL11.GL_NEAREST);
            glError = GL11.glGetError();

            if (glError != 0 && colorTexture > 0) {
                // 有些老驱动不接受"一次 blit 拷颜色+深度"（GL_SAME_FORMAT 检查更严）。
                // 退回只拷深度：光还能画，只是没法在着色器里采样场景色。
                drainGlError();
                GL30.glBlitFramebuffer(
                        0, 0, width, height,
                        0, 0, width, height,
                        GL11.GL_DEPTH_BUFFER_BIT,
                        GL11.GL_NEAREST);
                int depthOnlyError = GL11.glGetError();
                if (depthOnlyError == 0) {
                    if (!colorFallbackLogged) {
                        colorFallbackLogged = true;
                        JiezhiMod.warn("[SSS] 颜色+深度合并 blit 失败 glError=0x{}，"
                                + "已退回只拷深度 → 改用加法混合出图（场景色不可采样）。",
                                Integer.toHexString(glError));
                    }
                    colorCopied = false;
                    return true;
                }
                glError = depthOnlyError;
            }
        } finally {
            // ★ 无论 blit 是否抛异常，都必须在 finally 里把主 FBO 绑回读写目标。
            //   漏了这一步，后面所有绘制（包括 HUD）都会画进我们这张 FBO 里，
            //   表现是"画面整块消失 / 卡住上一帧"。
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, src.framebufferObject);
        }

        if (glError != 0) {
            // 最常见的两个来源：格式与源不匹配（DEPTH24_STENCIL8 vs DEPTH24）、
            // 或读/写只有一个绑上。错误码只打一次，避免刷屏。
            if (!blitErrorLogged) {
                blitErrorLogged = true;
                JiezhiMod.warn("[SSS] 深度 blit 失败 glError=0x{}（源格式={}，目标={}x{}）。"
                                + "SSS 放弃绘制，先修格式再谈画面。",
                        Integer.toHexString(glError),
                        src.isStencilEnabled() ? "DEPTH24_STENCIL8" : "DEPTH24", width, height);
            }
            return false;
        }
        colorCopied = colorTexture > 0;
        return true;
    }

    /**
     * 深度探针：把主 FBO 与拷贝纹理在同一个像素上的深度值读回来对比。
     *
     * <p>这是定位"blit 到底成没成"最快的手段 —— 两个数字相等就说明拷贝正确，
     * 之后画面不对就只可能是着色器或状态问题，不用再猜。</p>
     *
     * @param frame 当前帧号，调用方每 N 帧调一次即可（读回会打断流水线，别每帧干）
     */
    public void logProbe(Framebuffer src, int frame) {
        if (src == null || fbo == -1 || width <= 0 || height <= 0) {
            return;
        }
        int px = width / 2;
        int py = height / 3;             // 屏幕中下部，通常正好对着地面
        try {
            float fromMain = readDepth(src.framebufferObject, px, py);
            float fromCopy = readDepth(fbo, px, py);
            JiezhiMod.warn("[SSS] 深度探针 #{} @({},{}) 主FBO={} 拷贝={}",
                    frame, px, py, fmt(fromMain), fmt(fromCopy));
        } finally {
            // 探针把我们的 FBO 绑过，必须立刻还回主 FBO
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, src.framebufferObject);
        }
    }

    private float readDepth(int framebuffer, int x, int y) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(1);
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
        // 注意：这里**不许**动 glReadBuffer。主 FBO 的读缓冲被改成 GL_NONE 之后，
        // 原版 F2 截图（glReadPixels）会直接读不出东西。我们自己的 FBO 在创建时
        // 已经设成 GL_NONE 了。
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buffer);
        drainGlError();
        return buffer.get(0);
    }

    private static String fmt(float v) {
        return Float.isNaN(v) ? "NaN" : String.format("%.4f", Float.valueOf(v));
    }

    private static void drainGlError() {
        // 最多清 16 个，防止驱动一直报错时死循环
        for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
            // 只是清空
        }
    }


    /** 释放显存（退出世界 / 关闭功能时调） */
    public void dispose() {
        if (fbo != -1) {
            OpenGlHelper.glDeleteFramebuffers(fbo);
            fbo = -1;
        }
        if (depthTexture != -1) {
            GlStateManager.deleteTexture(depthTexture);
            depthTexture = -1;
        }
        if (colorTexture != -1) {
            GlStateManager.deleteTexture(colorTexture);
            colorTexture = -1;
        }
        colorCopied = false;
        width = -1;
        height = -1;
        srcFboId = -1;
        srcStencil = false;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static boolean canBlit() {
        Boolean cached = blitSupported;
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean ok;
        try {
            // 1.12.2 只保证 GL 2.1；blit 是 3.0 才进核心的，老驱动上是真的没有
            ok = GLContext.getCapabilities().OpenGL30
                    || GLContext.getCapabilities().GL_ARB_framebuffer_object;
        } catch (Throwable t) {
            ok = false;
        }
        blitSupported = Boolean.valueOf(ok);
        if (!ok) {
            JiezhiMod.warn("[SSS] 当前显卡/驱动没有 GL3.0 或 ARB_framebuffer_object，"
                    + "阶段 0 的深度 blit 不可用，SSS 管线将整体跳过。");
        }
        return ok;
    }

    private boolean ensureTarget(Framebuffer src) {
        int w = src.framebufferTextureWidth;
        int h = src.framebufferTextureHeight;
        boolean stencil = src.isStencilEnabled();

        if (fbo != -1 && src.framebufferObject == srcFboId
                && w == width && h == height && stencil == srcStencil) {
            return true;
        }

        dispose();
        if (!create(w, h, stencil)) {
            return false;
        }
        srcFboId = src.framebufferObject;
        width = w;
        height = h;
        srcStencil = stencil;
        return true;
    }

    private boolean create(int w, int h, boolean stencil) {
        // 与源的 renderbuffer 格式严格对齐，理由见类注释
        int internalFormat = stencil ? GL30.GL_DEPTH24_STENCIL8 : GL14.GL_DEPTH_COMPONENT24;
        int format = stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT;
        int type = stencil ? GL30.GL_UNSIGNED_INT_24_8 : GL11.GL_UNSIGNED_INT;

        depthTexture = GlStateManager.generateTexture();
        GlStateManager.bindTexture(depthTexture);
        GlStateManager.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat,
                w, h, 0, format, type, (IntBuffer) null);
        // 调试要看"每个像素的真实深度"，不能让硬件做 PCF / 双线性插值污染数值
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // 采样必须拿裸深度值，不能是"比较结果"（0/1）
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GlStateManager.bindTexture(0);

        // 场景色副本：格式必须与主 FBO 完全一致（RGBA8/RGBA/UNSIGNED_BYTE），否则 blit 报
        // INVALID_OPERATION；采样也是 1:1，用 NEAREST 免得把画面糊一层。
        colorTexture = GlStateManager.generateTexture();
        GlStateManager.bindTexture(colorTexture);
        GlStateManager.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (IntBuffer) null);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.bindTexture(0);

        fbo = OpenGlHelper.glGenFramebuffers();
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
        OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture, 0);
        OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTexture, 0);
        // ★ 有颜色附件之后 draw buffer 必须显式指向它 —— 留着默认值或 GL_NONE，
        //   颜色那一半 blit 会静默不生效（深度照拷，画面却只有灰度信息）。
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        // 读缓冲仍然关掉：本类的 FBO 只被 blit 写入，从来不需要从中读颜色。
        // 放在**创建时**设一次性，探针读深度时就不用再动它 ——
        // 那个状态一改错，原版 F2 截图会直接黑。
        GL11.glReadBuffer(GL11.GL_NONE);

        int status = OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER);
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);

        if (status != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE) {
            JiezhiMod.warn("[SSS] 深度 FBO 不完整（status=0x{}），SSS 管线放弃。",
                    Integer.toHexString(status));
            dispose();
            return false;
        }
        // 建好一次打一行：分辨率 / stencil / 格式都能对得上，后面排查就不用猜
        JiezhiMod.warn("[SSS] 目标就绪：{}x{} stencil={} depthFormat={} fbo={} depthTex={} colorTex={}",
                w, h, Boolean.valueOf(stencil),
                stencil ? "DEPTH24_STENCIL8" : "DEPTH24",
                Integer.valueOf(fbo), Integer.valueOf(depthTexture), Integer.valueOf(colorTexture));
        blitErrorLogged = false;
        colorFallbackLogged = false;
        return true;
    }

    /** {@code GL_TEXTURE_COMPARE_MODE}（GL 1.4 就有，但 LWJGL 的 GL11 里没导出） */
    private static final int GL_TEXTURE_COMPARE_MODE = 0x884C;
}
