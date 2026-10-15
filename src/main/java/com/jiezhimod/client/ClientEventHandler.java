package com.jiezhimod.client;

import com.jiezhimod.countdown.Countdown;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.List;

public class ClientEventHandler {
    private static final int SUBTITLE_LINE_HEIGHT = 12;
    /** 字幕入场动画时长（tick，20tick = 1秒） */
    private static final int SUBTITLE_ENTER_TICKS = 10;
    /** 字幕从底部滑入的初始距离（像素） */
    private static final int SUBTITLE_ENTER_DISTANCE = 42;

    /** 内置全屏模糊后处理 shader，用于模拟震撼弹白屏时的视觉模糊 */
    private static final ResourceLocation BLUR_SHADER = new ResourceLocation("shaders/post/blur.json");

    /** 记录模糊 shader 是否由本模组加载，避免误停别人的 shader */
    private boolean blurShaderLoaded = false;

    /** 上一 tick 实际施加到玩家视角的抖动偏移（用于增量抵消，避免视角漂移） */
    private float lastAppliedYaw = 0.0F;
    private float lastAppliedPitch = 0.0F;

    /** 每两个倒计时的垂直间距 */
    private static final int COUNTDOWN_ROW_HEIGHT = 30;
    /** 倒计时最后阶段的脉冲提醒阈值（tick，5 秒） */
    private static final int COUNTDOWN_PULSE_TICKS = 100;
    /** 样式6 进度条颜色开始转黄的阈值（tick，10 秒） */
    private static final int COUNTDOWN_WARN_TICKS = 200;
    /** 进入红色临界表态的阈值（tick，3 秒） */
    private static final int COUNTDOWN_CRITICAL_TICKS = 60;
    /** 样式6 进度条长度占屏幕宽度的比例（再做上下限夹取） */
    private static final float PROGRESS_BAR_WIDTH_RATIO = 0.28F;

    private static final float[] COLOR_RED = {1.0F, 0.28F, 0.28F};
    private static final float[] COLOR_YELLOW = {1.0F, 0.85F, 0.25F};
    private static final float[] COLOR_GREY = {0.38F, 0.38F, 0.38F};

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        ClientEffects.onClientTick();
        ClientCountdownManager.onClientTick();
        // 喷气尾焰粒子（本地玩家）
        com.jiezhimod.client.ClientJetRenderer.onClientTick(mc);

        EntityPlayerSP player = mc.player;
        if (player == null) {
            // 不在世界中（主菜单/断开连接）：确保模糊 shader 不会残留
            forceStopBlurShader(mc);
            resetShakeOffset();
            return;
        }

        applyShakeOffset(player);
        manageWhiteShader(mc);
    }

    /**
     * 以"增量"方式施加抖动偏移：先抵消上一 tick 施加的量，再叠加本 tick 的偏移。
     * 这样抖动结束后视角精确回到原位，不会像逐 tick 累加那样产生随机游走漂移。
     */
    private void applyShakeOffset(EntityPlayerSP player) {
        float yaw = ClientEffects.getShakeYawOffsetDeg();
        float pitch = ClientEffects.getShakePitchOffsetDeg();
        float dYaw = yaw - lastAppliedYaw;
        float dPitch = pitch - lastAppliedPitch;

        if (Math.abs(dYaw) > 0.0001F || Math.abs(dPitch) > 0.0001F) {
            player.rotationYaw += dYaw;
            player.rotationPitch += dPitch;
            player.prevRotationYaw += dYaw;
            player.prevRotationPitch += dPitch;
        }
        lastAppliedYaw = yaw;
        lastAppliedPitch = pitch;
    }

    private void resetShakeOffset() {
        lastAppliedYaw = 0.0F;
        lastAppliedPitch = 0.0F;
    }

    /**
     * 根据白屏状态加载/停止模糊 shader：
     *  - 白屏激活时加载内置 blur.json（全屏模糊，模拟震撼弹视觉冲击）
     *  - 白屏结束后停止 shader，恢复正常画面
     */
    private void manageWhiteShader(Minecraft mc) {
        if (mc.world == null || mc.player == null) {
            forceStopBlurShader(mc);
            return;
        }

        // 只在强闪阶段模糊；余像阶段（视网膜残留慢慢消退）保持清晰，更符合生理恢复过程
        if (ClientEffects.isWhiteBlurred()) {
            if (!blurShaderLoaded && !mc.entityRenderer.isShaderActive()) {
                try {
                    mc.entityRenderer.loadShader(BLUR_SHADER);
                    blurShaderLoaded = true;
                } catch (Throwable t) {
                    // 资源缺失 / 与其他模组冲突时静默降级：仅白屏，不模糊
                    blurShaderLoaded = false;
                }
            }
        } else {
            forceStopBlurShader(mc);
        }
    }

    private void forceStopBlurShader(Minecraft mc) {
        if (blurShaderLoaded) {
            try {
                mc.entityRenderer.stopUseShader();
            } catch (Throwable ignored) {
                // 忽略：shader 状态异常时不影响后续渲染
            }
            blurShaderLoaded = false;
        }
    }

    // ==================== 屏幕叠加渲染 ====================

    @SubscribeEvent
    public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            return;
        }

        drawWhiteScreen(event);
        drawCountdowns(event);
        drawSubtitles(event);
        // 喷气开关的热量条
        com.jiezhimod.client.JetHeatBarRenderer.draw(Minecraft.getMinecraft());
    }

    /**
     * 白屏震撼弹：整屏纯白覆盖，不做任何径向/光芒分层，保证峰值时是一块干净的全白。
     * 不透明度完全由曝光包络决定（爆闪冲峰 -> 快速衰减 -> 长尾余像），
     * 衰减过程中白色逐渐变透，露出下面的世界。
     */
    private void drawWhiteScreen(RenderGameOverlayEvent.Post event) {
        float alpha = ClientEffects.getWhiteAlpha();
        if (alpha <= 0.001F) {
            return;
        }

        int width = event.getResolution().getScaledWidth();
        int height = event.getResolution().getScaledHeight();

        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        // 整屏纯白：alpha=1 时为完全不透明的白（255,255,255）
        Gui.drawRect(0, 0, width, height, ((int) (alpha * 255.0F) << 24) | 0xFFFFFF);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    // ==================== 字幕 ====================

    private void drawSubtitles(RenderGameOverlayEvent.Post event) {
        List<ClientEffects.SubtitleLine> subtitles = ClientEffects.getSubtitles();
        if (subtitles.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        int width = event.getResolution().getScaledWidth();
        int height = event.getResolution().getScaledHeight();
        int baseY = height - 58;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

        int index = 0;
        for (ClientEffects.SubtitleLine subtitle : subtitles) {
            int alpha = getSubtitleAlpha(subtitle);
            String[] lines = splitLines(subtitle.text);
            if (alpha <= 0) {
                index += lines.length;
                continue;
            }

            // 入场动画：从底部丝滑滑入（easeOutCubic 缓动）
            float enterProgress = MathHelper.clamp(
                    (ClientEffects.getClientTick() - subtitle.startClientTick) / (float) SUBTITLE_ENTER_TICKS,
                    0.0F, 1.0F);
            float ease = 1.0F - (1.0F - enterProgress) * (1.0F - enterProgress) * (1.0F - enterProgress);
            int slide = (int) ((1.0F - ease) * SUBTITLE_ENTER_DISTANCE);

            for (int li = 0; li < lines.length; li++) {
                String text = lines[li];
                int textWidth = mc.fontRenderer.getStringWidth(text);
                int x = (width - textWidth) / 2;
                int targetY = baseY - index * SUBTITLE_LINE_HEIGHT;
                int y = targetY + slide;
                mc.fontRenderer.drawStringWithShadow(text, x, y, (alpha << 24) | 0xFFFFFF);
                index++;
            }
        }

        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    /** 支持用字面 \n 换行（1.12.2 的指令参数无法输入真实换行符） */
    private static String[] splitLines(String text) {
        String normalized = text.replace("\\n", "\n");
        return normalized.split("\n", -1);
    }

    private int getSubtitleAlpha(ClientEffects.SubtitleLine subtitle) {
        int clientTick = ClientEffects.getClientTick();
        int remaining = subtitle.expireAtClientTick - clientTick;
        if (remaining <= 0) {
            return 0;
        }

        // 末尾淡出
        int fadeOut = 10;
        if (remaining <= fadeOut) {
            return MathHelper.clamp((int) (255.0F * (remaining / (float) fadeOut)), 0, 255);
        }

        // 入场淡入（与底部滑入同步，更丝滑）
        int elapsed = clientTick - subtitle.startClientTick;
        if (elapsed < SUBTITLE_ENTER_TICKS) {
            return MathHelper.clamp((int) (255.0F * (elapsed / (float) SUBTITLE_ENTER_TICKS)), 0, 255);
        }

        return 255;
    }

    // ==================== 倒计时渲染（屏幕上方中央） ====================

    private void drawCountdowns(RenderGameOverlayEvent.Post event) {
        List<Countdown> list = ClientCountdownManager.getList();
        if (list.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int width = event.getResolution().getScaledWidth();
        float partial = MathHelper.clamp(event.getPartialTicks(), 0.0F, 1.0F);
        int y = 8;
        for (Countdown c : list) {
            boolean running = !c.finished && !c.paused && c.remainingTicks > 0;
            // 高精度样式把 partialTicks 计入剩余量，让 0.1s 位连续走动而不是每 tick 硬跳一格
            String time = Countdown.isPreciseStyle(c.style)
                    ? formatCountdownTimePrecise(remainingForDisplay(c, running ? partial : 0.0F))
                    : formatCountdownTime(c.remainingTicks);
            boolean urgent = running && c.remainingTicks <= COUNTDOWN_PULSE_TICKS;
            float scale = urgent ? 1.0F + 0.10F * (float) Math.sin(ClientEffects.getClientTick() * 0.35D) : 1.0F;

            GlStateManager.pushMatrix();
            if (scale != 1.0F) {
                // 以本行中心为锚点缩放，形成"临期心跳"脉冲
                GlStateManager.translate(width / 2.0F, y + 6.0F, 0.0F);
                GlStateManager.scale(scale, scale, 1.0F);
                GlStateManager.translate(-width / 2.0F, -(y + 6.0F), 0.0F);
            }

            switch (c.style) {
                case 1:
                    drawCountdownStyle1(mc, time, c.rgb, width, y, c.finished);
                    break;
                case 2:
                    drawCountdownStyle2(mc, time, width, y, c.finished);
                    break;
                case 3:
                    drawCountdownStyle3(mc, time, c.rgb, width, y, c.finished);
                    break;
                case 4:
                    drawCountdownStyle4(mc, time, c.rgb, width, y, c);
                    break;
                case 5:
                    drawCountdownStyle5(mc, time, c.rgb, width, y, c);
                    break;
                case 6:
                    drawCountdownStyle6(mc, time, c.rgb, width, y, c, running ? partial : 0.0F);
                    break;
                default:
                    drawCountdownStyle1(mc, time, c.rgb, width, y, c.finished);
            }

            GlStateManager.popMatrix();
            y += COUNTDOWN_ROW_HEIGHT;
        }
    }

    /** 渲染用剩余 tick：运行中扣掉帧间 partialTicks 做平滑；暂停/结束时用整 tick */
    private static double remainingForDisplay(Countdown c, float partial) {
        if (c.paused || c.finished || c.remainingTicks <= 0) {
            return c.remainingTicks;
        }
        return Math.max(0.0D, c.remainingTicks - partial);
    }

    /**
     * mm:ss（向上取整）：3 秒倒计时立刻显示 03 而不是 02，最后 1 tick 也显示 00:01。
     */
    private static String formatCountdownTime(long ticks) {
        long secs = remainingSeconds(ticks);
        return composeTime(secs, false, 0L);
    }

    /**
     * mm:ss.t（十分之一秒，向上取整）。传入的剩余 tick 可为小数（已扣除 partialTicks）。
     */
    private static String formatCountdownTimePrecise(double ticksRemaining) {
        double tenthsTotal = ticksRemaining <= 0.0D ? 0.0D : Math.ceil(ticksRemaining / 2.0D - 1e-9D);
        long tenths = (long) Math.max(0.0D, tenthsTotal);
        long secs = tenths / 10L;
        return composeTime(secs, true, tenths % 10L);
    }

    private static long remainingSeconds(long ticks) {
        if (ticks <= 0) {
            return 0L;
        }
        long t = ticks - 1L;              // 让最后一 tick 仍显示 1 秒，而不是提前跳到 0
        return t / 20L + 1L;
    }

    private static String composeTime(long totalSeconds, boolean precise, long fraction) {
        long h = totalSeconds / 3600L;
        long m = (totalSeconds % 3600L) / 60L;
        long s = totalSeconds % 60L;
        String base = h > 0 ? String.format("%02d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
        return precise ? base + "." + fraction : base;
    }

    /** 把 "mm:ss.t" 拆成 [主体含小数点, 小数位]，用于给十分之一秒单独上色 */
    private static String[] splitFraction(String time) {
        int dot = time.lastIndexOf('.');
        if (dot < 0 || dot >= time.length() - 1) {
            return new String[]{time, ""};
        }
        return new String[]{time.substring(0, dot + 1), time.substring(dot + 1)};
    }

    /** 样式1：普通时间文本 + 下方一条横线（rgb 颜色） */
    private void drawCountdownStyle1(Minecraft mc, String time, float[] rgb, int width, int y, boolean finished) {
        int textWidth = mc.fontRenderer.getStringWidth(time);
        int x = (width - textWidth) / 2;
        int color = toColor(rgb, 255, finished);
        mc.fontRenderer.drawStringWithShadow(time, x, y, color);
        GlStateManager.disableTexture2D();
        Gui.drawRect(x - 4, y + 11, x + textWidth + 4, y + 12, color);
        GlStateManager.enableTexture2D();
    }

    /**
     * 样式2：\\\时间/// 整体红色，斜杠以对称方式从外向内一个一个高亮（亮红），
     * 采用 暗->亮->暗 的往返（ping-pong）循环，动画更连贯；倒计时结束后静止为暗红。
     */
    private void drawCountdownStyle2(Minecraft mc, String time, int width, int y, boolean finished) {
        int lit = 0;
        if (!finished) {
            int period = 3;                                  // 0,1,2 三层
            int step = (ClientEffects.getClientTick() / 4) % (period * 2);
            lit = step <= period ? step : (period * 2 - step); // 往返：0,1,2,3,2,1
            lit = Math.max(1, lit);                          // 至少亮一层，避免整条全暗像卡住
        } else {
            lit = 0;
        }

        StringBuilder sb = new StringBuilder();
        // 左侧斜杠：从左到右 = 外层→内层
        for (int i = 0; i < 3; i++) {
            sb.append(i < lit ? "\u00a7c\\" : "\u00a74\\");
        }
        sb.append("\u00a7c").append(time);
        // 右侧斜杠：渲染顺序从左到右 = 内层→外层
        for (int i = 0; i < 3; i++) {
            sb.append((2 - i) < lit ? "\u00a7c/" : "\u00a74/");
        }
        String render = sb.toString();
        int textWidth = mc.fontRenderer.getStringWidth(render);
        int x = (width - textWidth) / 2;
        mc.fontRenderer.drawStringWithShadow(render, x, y, 0xFFFFFFFF);
    }

    /** 样式3：矩形框框圈住时间（rgb 颜色） */
    private void drawCountdownStyle3(Minecraft mc, String time, float[] rgb, int width, int y, boolean finished) {
        int textWidth = mc.fontRenderer.getStringWidth(time);
        int x = (width - textWidth) / 2;
        int color = toColor(rgb, 255, finished);
        mc.fontRenderer.drawStringWithShadow(time, x, y, color);

        int pad = 5;
        int x0 = x - pad;
        int y0 = y - 3;
        int x1 = x + textWidth + pad;
        int y1 = y + 12;
        GlStateManager.disableTexture2D();
        Gui.drawRect(x0, y0, x1, y0 + 1, color);     // 上边
        Gui.drawRect(x0, y1 - 1, x1, y1, color);     // 下边
        Gui.drawRect(x0, y0, x0 + 1, y1, color);     // 左边
        Gui.drawRect(x1 - 1, y0, x1, y1, color);     // 右边
        GlStateManager.enableTexture2D();
    }

    /**
     * 样式4：高精度 mm:ss.t —— 十分之一秒独立上色。
     * 10 秒内小数位转黄、3 秒内转红，主体保持 rgb；结束后整体压暗。
     */
    private void drawCountdownStyle4(Minecraft mc, String time, float[] rgb, int width, int y, Countdown c) {
        String[] parts = splitFraction(time);
        boolean running = !c.finished && !c.paused && c.remainingTicks > 0;
        boolean soon = running && c.remainingTicks <= COUNTDOWN_WARN_TICKS;
        boolean critical = running && c.remainingTicks <= COUNTDOWN_CRITICAL_TICKS;

        int mainColor = toColor(rgb, 255, c.finished ? 0.45F : 1.0F);
        int fracColor;
        if (c.finished) {
            fracColor = mainColor;
        } else if (critical) {
            fracColor = toColor(COLOR_RED, 255, 1.0F);
        } else if (soon) {
            fracColor = toColor(COLOR_YELLOW, 255, 1.0F);
        } else {
            fracColor = mainColor;
        }

        int wMain = mc.fontRenderer.getStringWidth(parts[0]);
        int wFrac = mc.fontRenderer.getStringWidth(parts[1]);
        int x = (width - (wMain + wFrac)) / 2;
        mc.fontRenderer.drawStringWithShadow(parts[0], x, y, mainColor);
        mc.fontRenderer.drawStringWithShadow(parts[1], x + wMain, y, fracColor);
    }

    /**
     * 样式5：!mm:ss.t! —— 两侧感叹号包裹的高精度倒计时。
     * 最后 5 秒感叹号开始明暗脉冲，3 秒内整体转红且脉冲加快，形成"警报"观感。
     */
    private void drawCountdownStyle5(Minecraft mc, String time, float[] rgb, int width, int y, Countdown c) {
        boolean running = !c.finished && !c.paused && c.remainingTicks > 0;
        boolean warn = running && c.remainingTicks <= COUNTDOWN_PULSE_TICKS;
        boolean critical = running && c.remainingTicks <= COUNTDOWN_CRITICAL_TICKS;

        String bang = "!";
        int wBang = mc.fontRenderer.getStringWidth(bang);
        int gap = 3;
        String[] parts = splitFraction(time);
        int wMain = mc.fontRenderer.getStringWidth(parts[0]);
        int wFrac = mc.fontRenderer.getStringWidth(parts[1]);

        int totalW = wBang + gap + wMain + wFrac + gap + wBang;
        int cx = (width - totalW) / 2;

        // 感叹号亮度：5 秒内缓慢明灭，3 秒内加快闪烁；结束后静止
        float bangBrightness = c.finished ? 0.45F : 1.0F;
        if (warn) {
            double speed = critical ? 0.85D : 0.40D;
            float s = (float) (Math.sin(ClientEffects.getClientTick() * speed) * 0.5D + 0.5D);
            bangBrightness *= 0.25F + 0.75F * s;
        }
        int bangColor = toColor(critical ? COLOR_RED : rgb, 255, Math.max(0.12F, bangBrightness));

        int textColor = toColor(critical ? COLOR_RED : rgb, 255, c.finished ? 0.45F : 1.0F);
        int fracColor;
        if (c.finished) {
            fracColor = textColor;
        } else if (critical) {
            fracColor = toColor(COLOR_RED, 255, 1.0F);
        } else if (warn) {
            fracColor = toColor(COLOR_YELLOW, 255, 1.0F);
        } else {
            fracColor = textColor;
        }

        mc.fontRenderer.drawStringWithShadow(bang, cx, y, bangColor);
        cx += wBang + gap;
        mc.fontRenderer.drawStringWithShadow(parts[0], cx, y, textColor);
        mc.fontRenderer.drawStringWithShadow(parts[1], cx + wMain, y, fracColor);
        cx += wMain + wFrac + gap;
        mc.fontRenderer.drawStringWithShadow(bang, cx, y, bangColor);
    }

    /**
     * 样式6：mm:ss.t + 下方收缩进度条。
     * 条的填充比例 = 剩余/总时长（用 partialTicks 平滑），颜色由用户 rgb 过渡到黄（10 秒内）、红（3 秒内呼吸闪烁）。
     */
    private void drawCountdownStyle6(Minecraft mc, String time, float[] rgb, int width, int y,
                                     Countdown c, float partial) {
        boolean running = !c.finished && !c.paused && c.remainingTicks > 0;
        boolean critical = running && c.remainingTicks <= COUNTDOWN_CRITICAL_TICKS;

        String[] parts = splitFraction(time);
        int textColor = toColor(rgb, 255, c.finished ? 0.45F : 1.0F);
        int fracColor = c.finished ? textColor
                : toColor(critical ? COLOR_RED : (running && c.remainingTicks <= COUNTDOWN_WARN_TICKS
                        ? mixColor(rgb, COLOR_YELLOW, 1.0F - (c.remainingTicks - COUNTDOWN_CRITICAL_TICKS) / 140.0F)
                        : rgb), 255, 1.0F);
        int wMain = mc.fontRenderer.getStringWidth(parts[0]);
        int wFrac = mc.fontRenderer.getStringWidth(parts[1]);
        int textX = (width - (wMain + wFrac)) / 2;
        mc.fontRenderer.drawStringWithShadow(parts[0], textX, y, textColor);
        mc.fontRenderer.drawStringWithShadow(parts[1], textX + wMain, y, fracColor);

        int barWidth = MathHelper.clamp((int) (width * PROGRESS_BAR_WIDTH_RATIO), 64, 180);
        int barHeight = 3;
        int x0 = (width - barWidth) / 2;
        int barY = y + 12;

        long total = Math.max(1L, c.totalTicks);
        double rem = remainingForDisplay(c, running ? partial : 0.0F);
        float ratio = MathHelper.clamp((float) (rem / (double) total), 0.0F, 1.0F);

        int frameColor = toColor(rgb, 255, c.finished ? 0.30F : 0.60F);
        GlStateManager.disableTexture2D();
        Gui.drawRect(x0 - 1, barY - 1, x0 + barWidth + 1, barY + barHeight + 1, frameColor);   // 外框
        Gui.drawRect(x0, barY, x0 + barWidth, barY + barHeight, 0x99000000);                   // 轨道底
        int fillWidth = (int) Math.round(barWidth * ratio);
        if (fillWidth > 0) {
            float[] fillRgb = rgb;
            float brightness = 1.0F;
            if (c.finished) {
                fillRgb = COLOR_GREY;
                brightness = 0.7F;
            } else if (critical) {
                fillRgb = COLOR_RED;
                brightness = 0.45F + 0.55F * (float) (Math.sin(ClientEffects.getClientTick() * 0.60D) * 0.5D + 0.5D);
            } else if (running && c.remainingTicks <= COUNTDOWN_WARN_TICKS) {
                fillRgb = mixColor(rgb, COLOR_YELLOW,
                        1.0F - (c.remainingTicks - COUNTDOWN_CRITICAL_TICKS) / 140.0F);
            }
            Gui.drawRect(x0, barY, x0 + fillWidth, barY + barHeight,
                    toColor(fillRgb, 255, Math.max(0.2F, brightness)));
        }
        GlStateManager.enableTexture2D();
    }

    private static float[] mixColor(float[] a, float[] b, float t) {
        float k = MathHelper.clamp(t, 0.0F, 1.0F);
        return new float[]{
                a[0] + (b[0] - a[0]) * k,
                a[1] + (b[1] - a[1]) * k,
                a[2] + (b[2] - a[2]) * k
        };
    }

    /** 结束后整体压暗，与进行中的倒计时区分开 */
    private static int toColor(float[] rgb, int alpha, boolean finished) {
        return toColor(rgb, alpha, finished ? 0.45F : 1.0F);
    }

    /** rgb(0-1) × 亮度 → ARGB；alpha 需为 0-255 */
    private static int toColor(float[] rgb, int alpha, float brightness) {
        int r = MathHelper.clamp((int) (rgb[0] * 255.0F * brightness), 0, 255);
        int g = MathHelper.clamp((int) (rgb[1] * 255.0F * brightness), 0, 255);
        int b = MathHelper.clamp((int) (rgb[2] * 255.0F * brightness), 0, 255);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }
}
