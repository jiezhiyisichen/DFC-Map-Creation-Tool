package com.jiezhimod.dfc;

/**
 * 屏幕覆盖弹窗的**纯逻辑**：什么时候弹、弹多久、箭头跳到第几批、倒计时怎么显示。
 *
 * 【为什么单独一个类、一个 MC 类都不 import】
 * 弹窗是"进入熔毁 / 引爆器启动"时覆盖在所有页面上的一条警告带，
 * 它的**状态机**（按快照决定弹不弹、弹入弹出的进度、箭头脉冲）如果写进渲染代码里，
 * 就只能靠进游戏看效果来调 —— 而这轮正是没条件反复实机验证。
 * 抽出来之后，`SelfTest` 可以直接断言：
 * "熔毁优先于引爆 / 没倒计时就不弹 / 弹入进度单调且落在 0~1 / 箭头按 3→2→1 循环 / m:ss 格式化"。
 */
public final class OverlayBanner {

    /** 不弹 */
    public static final int KIND_NONE = 0;
    /** 熔毁倒计时（红） */
    public static final int KIND_MELTDOWN = 1;
    /** 引爆器已启动（橙） */
    public static final int KIND_DETONATE = 2;

    /** 弹入动画时长（毫秒） */
    public static final long IN_MS = 260L;
    /** 弹出动画时长（毫秒） */
    public static final long OUT_MS = 300L;
    /** 箭头脉冲周期（毫秒） */
    public static final long ARROW_PERIOD_MS = 420L;

    private OverlayBanner() {
    }

    /**
     * 按快照决定弹哪一种。
     *
     * 【优先级】引爆器优先于普通熔毁 —— 玩家主动拉了引爆器，屏幕上应该明确告诉他是"引爆"，
     * 而不是笼统的"熔毁"。两者都用同一份坍缩度换算倒计时（核心真炸的进度只有一个）。
     *
     * @param collapsing 坍缩度 0~1（作者公式 {@code clamp(1 - explosionIn/120, 0, 1)}）
     * @param detonation 是否进入引爆程序（{@code getDetonation}）
     * @param linked     是否连接到了核心；没连接就不弹
     */
    public static int decide(double collapsing, boolean detonation, boolean linked) {
        if (!linked) {
            return KIND_NONE;
        }
        if (detonation) {
            return KIND_DETONATE;
        }
        if (collapsing > 0.0D) {
            return KIND_MELTDOWN;
        }
        return KIND_NONE;
    }

    /**
     * 剩余秒数。
     *
     * 【为什么用坍缩度反算，而不是直接用 explosionIn】
     * {@code explosionIn} 的单位在作者代码里没有明确注释（秒还是 tick 说不清），
     * 但 {@code collapsing = 1 - explosionIn/120} 这个换算关系是确定的 ——
     * 于是 {@code (1 - collapsing) * 120} 一定是"还剩多少秒"，与它的单位无关。
     * 显示一个确定正确的数，比显示一个可能差 20 倍的数重要。
     */
    public static int remainSeconds(double collapsing) {
        if (collapsing <= 0.0D) {
            return -1;
        }
        double left = (1.0D - Math.min(1.0D, collapsing)) * 120.0D;
        return (int) Math.max(0L, Math.round(left));
    }

    /** 倒计时文本：{@code 3:14}；负数 = 没有倒计时，返回 {@code --:--} */
    public static String mmss(int totalSeconds) {
        if (totalSeconds < 0) {
            return "--:--";
        }
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        return String.format(java.util.Locale.ROOT, "%d:%02d", m, s);
    }

    /**
     * 弹入进度 0~1（缓出）。超过 {@link #IN_MS} 后恒为 1。
     */
    public static float inProgress(long msSinceStart) {
        if (msSinceStart <= 0L) {
            return 0.0F;
        }
        if (msSinceStart >= IN_MS) {
            return 1.0F;
        }
        float t = msSinceStart / (float) IN_MS;
        // 缓出：easeOutCubic
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;
    }

    /** 弹出进度 0~1（线性）；超过 {@link #OUT_MS} 后恒为 1 */
    public static float outProgress(long msSinceEnd) {
        if (msSinceEnd <= 0L) {
            return 0.0F;
        }
        if (msSinceEnd >= OUT_MS) {
            return 1.0F;
        }
        return msSinceEnd / (float) OUT_MS;
    }

    /**
     * 箭头批数：3 → 2 → 1 循环。
     *
     * 参考截图里 {@code >>>> 3:14 <<<<} 的箭头会一段段往里收，
     * 用"每 {@link #ARROW_PERIOD_MS} 换一批"实现，不必逐帧算位置。
     */
    public static int arrowBatch(long nowMs) {
        long phase = Math.floorMod(nowMs, ARROW_PERIOD_MS * 3L) / ARROW_PERIOD_MS;
        return 3 - (int) phase;
    }

    /**
     * 闪烁强度 0~1：边框/光晕用。
     * 周期 620ms，取绝对值正弦 —— 有"心跳"感但不会闪到看不清。
     */
    public static float pulse(long nowMs) {
        double t = (nowMs % 620L) / 620.0D;
        return (float) Math.abs(Math.sin(t * Math.PI));
    }
}
