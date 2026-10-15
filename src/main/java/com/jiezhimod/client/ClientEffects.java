package com.jiezhimod.client;

import net.minecraft.util.math.MathHelper;

import java.util.LinkedList;
import java.util.Random;

/**
 * 客户端特效状态机：视角抖动 / 白屏震撼弹 / 字幕。
 *
 * 抖动设计要点：
 *  1. 用多正弦叠加的平滑噪声代替逐 tick 纯随机，手感是"晃动/震动"而不是"抽搐噪点"；
 *  2. 三段包络 attack -> 主衰减 -> 余震：3 tick 冲到峰值，主段按指数快速衰减到
 *     SHAKE_TAIL_LEVEL，后半段是缓慢消退的低幅余震（真实爆炸/地震都是这个形状：
 *     冲击瞬间最强，随后长时间小幅晃动）；
 *  3. 噪声推进速度随包络变慢（强震时晃得快，余震时晃得慢），高频颗粒感也同步减弱；
 *  4. 输出的是"相对基准视角的瞬时偏移"，由 ClientEventHandler 用增量方式施加，
 *     抖动结束后视角回到原位（旧实现逐 tick 累加随机量，会产生随机游走漂移）。
 *
 * 白屏设计要点：
 *  1. 3 tick 爆闪冲满，主段指数衰减到 FLASH_TAIL_LEVEL；
 *  2. 主闪结束后自动追加一段余像（afterimage，最长 90 tick）：模拟强光在视网膜上的
 *     残留慢慢消退 —— 真实闪光弹被闪后要几秒才恢复，不是"啪"地一下黑回来；
 *  3. 模糊 shader 只在强闪阶段开启，余像阶段画面清晰、只剩淡白残留。
 */
public class ClientEffects {
    private static final Random RAND = new Random();

    /** 抖动起振时长（tick）：快速冲到峰值，模拟爆炸瞬间冲击 */
    private static final int SHAKE_ATTACK_TICKS = 3;
    /** 抖动噪声基频与倍频（越大越"细碎"） */
    private static final double SHAKE_F1 = 0.38D;
    private static final double SHAKE_F2 = 0.83D;
    private static final double SHAKE_F3 = 1.71D;
    /** 叠加在平滑噪声上的高频抖动比例（保留一点真实震动的颗粒感） */
    private static final float SHAKE_JITTER = 0.18F;
    /** 默认抖动强度（度）：/shake 只给 ticks 时使用 */
    public static final float SHAKE_DEFAULT_INTENSITY = 4.0F;
    /** 主震占整个抖动时长的比例，之后是余震段 */
    private static final float SHAKE_MAIN_FRACTION = 0.5F;
    /** 主震衰减到的余震平台（相对峰值） */
    private static final float SHAKE_TAIL_LEVEL = 0.28F;
    /** 主震指数衰减速率（越大衰减越快） */
    private static final float SHAKE_DECAY_K = 3.2F;

    /** 白屏爆闪起闪时长（tick）：约 2 帧冲满，模拟闪光弹瞬间致盲又留一点 bloom 过渡 */
    private static final int FLASH_ATTACK_TICKS = 3;
    /** 白屏默认不透明度：/white 只给 ticks 时使用 */
    public static final float WHITE_DEFAULT_ALPHA = 1.0F;
    /** 主闪衰减到的余像平台（相对峰值）：之后进入缓慢消退的残留期 */
    private static final float FLASH_TAIL_LEVEL = 0.22F;
    /** 主闪指数衰减速率 */
    private static final float FLASH_DECAY_K = 3.6F;
    /** 余像段长度上下限（tick）：越强的闪光残留越久，但不超过 4.5 秒 */
    private static final int FLASH_AFTER_MIN = 20;
    private static final int FLASH_AFTER_MAX = 90;

    // ==================== 视角抖动 ====================
    private static int shakeTicksLeft = 0;
    private static int shakeTicksTotal = 0;
    private static float shakeIntensityDeg = 0.0f;
    /** 每次起振随机相位，避免两次抖动看起来一模一样 */
    private static final double[] shakePhase = new double[6];
    /** 噪声相位推进量：强震时走得快，余震时走得慢 */
    private static float shakeNoiseTime = 0.0F;

    // ==================== 白屏 ====================
    private static int whiteTicksLeft = 0;
    private static int whiteTicksTotal = 0;
    /** 用户请求的主闪时长；whiteTicksTotal = 主闪 + 自动追加的余像段 */
    private static int whiteFlashTicks = 0;
    private static float whiteAlpha = 0.0f;

    // ==================== 字幕（快捷栏上方） ====================
    public static class SubtitleLine {
        public final String text;
        public final int startClientTick;
        public final int expireAtClientTick;

        public SubtitleLine(String text, int startClientTick, int expireAtClientTick) {
            this.text = text;
            this.startClientTick = startClientTick;
            this.expireAtClientTick = expireAtClientTick;
        }
    }

    private static int clientTick = 0;
    private static final LinkedList<SubtitleLine> subtitles = new LinkedList<>();

    public static void onClientTick() {
        clientTick++;

        if (shakeTicksLeft > 0) {
            shakeTicksLeft--;
            if (shakeTicksLeft <= 0) {
                shakeTicksTotal = 0;
                shakeIntensityDeg = 0.0f;
                shakeNoiseTime = 0.0F;
            } else {
                // 噪声推进速度随包络变化：强震时晃得快（2.0x），余震时晃得慢（0.55x）
                shakeNoiseTime += 0.55F + 1.45F * getShakeEnvelope();
            }
        }

        if (whiteTicksLeft > 0) {
            whiteTicksLeft--;
            if (whiteTicksLeft <= 0) {
                whiteTicksTotal = 0;
                whiteFlashTicks = 0;
                whiteAlpha = 0.0f;
            }
        }

        // 清理过期字幕
        subtitles.removeIf(s -> s.expireAtClientTick <= clientTick);
    }

    public static void startShake(int ticks, float intensityDeg) {
        ticks = Math.max(0, ticks);
        intensityDeg = Math.max(0.0f, intensityDeg);

        if (ticks > shakeTicksLeft || intensityDeg > shakeIntensityDeg) {
            shakeTicksLeft = ticks;
            shakeTicksTotal = ticks;
            shakeIntensityDeg = intensityDeg;
            shakeNoiseTime = 0.0F;
            // 重新随机相位：yaw/pitch 各三条正弦
            for (int i = 0; i < shakePhase.length; i++) {
                shakePhase[i] = RAND.nextDouble() * Math.PI * 2.0D;
            }
        }
    }

    /**
     * 开启白屏：主闪 ticks（用户指定）+ 自动追加的余像段。
     * 余像长度随主闪时长增加（20~90 tick），越强的闪光残留越久。
     */
    public static void startWhiteScreen(int ticks, float alpha) {
        ticks = Math.max(0, ticks);
        alpha = MathHelper.clamp(alpha, 0.0f, 1.0f);

        if (ticks > whiteFlashTicks || alpha > whiteAlpha) {
            int main = Math.max(1, ticks);
            int after = Math.min(FLASH_AFTER_MAX, Math.max(FLASH_AFTER_MIN, main));
            whiteFlashTicks = main;
            whiteTicksTotal = main + after;
            whiteTicksLeft = whiteTicksTotal;
            whiteAlpha = alpha;
        }
    }

    public static void addSubtitle(String text, int ticks) {
        if (text == null) {
            return;
        }
        text = text.trim();
        if (text.isEmpty()) {
            return;
        }

        ticks = Math.max(1, ticks);
        subtitles.addLast(new SubtitleLine(text, clientTick, clientTick + ticks));
    }

    public static void clearSubtitles() {
        subtitles.clear();
    }

    /**
     * 当前抖动包络（0~1）：起振 -> 主震指数衰减 -> 余震缓慢消退。
     * 相比单纯的幂次衰减，后半段保留了 SHAKE_TAIL_LEVEL 起的低幅余震，
     * 更接近真实爆炸/地震的"冲击 + 长时间小幅晃动"。
     */
    public static float getShakeEnvelope() {
        if (shakeTicksLeft <= 0 || shakeTicksTotal <= 0) {
            return 0.0F;
        }
        int elapsed = shakeTicksTotal - shakeTicksLeft;

        if (elapsed < SHAKE_ATTACK_TICKS) {
            return MathHelper.clamp((elapsed + 1) / (float) SHAKE_ATTACK_TICKS, 0.0F, 1.0F);
        }

        float main = Math.max(SHAKE_ATTACK_TICKS + 1.0F, shakeTicksTotal * SHAKE_MAIN_FRACTION);
        float q = (elapsed - SHAKE_ATTACK_TICKS) / (main - SHAKE_ATTACK_TICKS);
        if (q < 1.0F) {
            return SHAKE_TAIL_LEVEL + (1.0F - SHAKE_TAIL_LEVEL) * expNormalized(q, SHAKE_DECAY_K);
        }
        // 余震段：从平台值缓慢消退到 0
        float r = MathHelper.clamp((elapsed - main) / Math.max(1.0F, shakeTicksTotal - main), 0.0F, 1.0F);
        return SHAKE_TAIL_LEVEL * (float) Math.pow(1.0F - r, 1.5F);
    }

    /**
     * 归一化指数衰减：q=0 -> 1，q=1 -> 0，中间按 exp(-k*q) 形状（先快后慢）。
     */
    private static float expNormalized(float q, float k) {
        q = MathHelper.clamp(q, 0.0F, 1.0F);
        double lo = Math.exp(-k);
        return (float) ((Math.exp(-k * q) - lo) / (1.0D - lo));
    }

    /** 平滑噪声：多正弦叠加，值域约 [-1, 1] */
    private static float smoothNoise(double t, double p1, double p2, double p3) {
        double v = Math.sin(t * SHAKE_F1 + p1)
                + Math.sin(t * SHAKE_F2 + p2) * 0.55D
                + Math.sin(t * SHAKE_F3 + p3) * 0.28D;
        return (float) (v / 1.83D);
    }

    /** 抖动偏移量（度）：相对玩家基准视角的瞬时偏移，正负交替 */
    public static float getShakeYawOffsetDeg() {
        if (shakeTicksLeft <= 0 || shakeIntensityDeg <= 0.0F) {
            return 0.0F;
        }
        float env = getShakeEnvelope();
        if (env <= 0.0F) {
            return 0.0F;
        }
        float n = smoothNoise(shakeNoiseTime, shakePhase[0], shakePhase[1], shakePhase[2]);
        float jitter = (RAND.nextFloat() * 2.0F - 1.0F) * SHAKE_JITTER * (0.35F + 0.65F * env);
        return (n + jitter) * shakeIntensityDeg * env;
    }

    /** 抖动俯仰偏移量（度）：与 yaw 使用不同相位，形成椭圆轨迹而非直线抖动 */
    public static float getShakePitchOffsetDeg() {
        if (shakeTicksLeft <= 0 || shakeIntensityDeg <= 0.0F) {
            return 0.0F;
        }
        float env = getShakeEnvelope();
        if (env <= 0.0F) {
            return 0.0F;
        }
        float n = smoothNoise(shakeNoiseTime, shakePhase[3], shakePhase[4], shakePhase[5]);
        float jitter = (RAND.nextFloat() * 2.0F - 1.0F) * SHAKE_JITTER * (0.35F + 0.65F * env);
        // pitch 幅度略小，视觉上更符合手持持枪/头部晃动的体感
        return (n + jitter) * shakeIntensityDeg * 0.75F * env;
    }

    /**
     * 白屏当前不透明度（0~1）：爆闪冲满 -> 主闪指数衰减 -> 余像缓慢消退。
     */
    public static float getWhiteAlpha() {
        if (whiteTicksLeft <= 0 || whiteAlpha <= 0.0F) {
            return 0.0F;
        }
        return whiteAlpha * getWhiteEnvelope();
    }

    /**
     * 白屏包络（0~1）：爆闪 -> 主闪衰减到 FLASH_TAIL_LEVEL -> 余像段缓慢归零。
     */
    public static float getWhiteEnvelope() {
        if (whiteTicksLeft <= 0 || whiteTicksTotal <= 0) {
            return 0.0F;
        }
        int elapsed = whiteTicksTotal - whiteTicksLeft;

        if (elapsed < FLASH_ATTACK_TICKS) {
            return MathHelper.clamp((elapsed + 1) / (float) FLASH_ATTACK_TICKS, 0.0F, 1.0F);
        }

        float main = Math.max(FLASH_ATTACK_TICKS + 1.0F, whiteFlashTicks);
        float q = (elapsed - FLASH_ATTACK_TICKS) / (main - FLASH_ATTACK_TICKS);
        if (q < 1.0F) {
            return FLASH_TAIL_LEVEL + (1.0F - FLASH_TAIL_LEVEL) * expNormalized(q, FLASH_DECAY_K);
        }
        // 余像段：视网膜残留缓慢消退
        float r = MathHelper.clamp((elapsed - main) / Math.max(1.0F, whiteTicksTotal - main), 0.0F, 1.0F);
        return FLASH_TAIL_LEVEL * (float) Math.pow(1.0F - r, 1.8F);
    }

    /** 当前是否处于余像（残留）阶段：主闪已过，只剩淡淡的残留 */
    public static boolean isWhiteAfterimage() {
        return isWhiteActive() && whiteFlashTicks > 0
                && (whiteTicksTotal - whiteTicksLeft) > whiteFlashTicks;
    }

    /** 白屏效果是否处于激活状态（用于控制模糊 shader 的加载/停止） */
    public static boolean isWhiteActive() {
        return whiteTicksLeft > 0 && whiteAlpha > 0.0f;
    }

    /** 只有强闪阶段才加载模糊 shader；余像阶段画面保持清晰 */
    public static boolean isWhiteBlurred() {
        return isWhiteActive() && getWhiteEnvelope() > 0.30F;
    }

    public static LinkedList<SubtitleLine> getSubtitles() {
        return subtitles;
    }

    public static int getClientTick() {
        return clientTick;
    }
}
