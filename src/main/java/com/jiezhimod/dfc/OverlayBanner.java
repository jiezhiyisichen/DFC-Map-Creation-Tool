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
    /**
     * **熔毁进行中**（核心已经在坍缩）。
     *
     * 这个阶段玩家最需要看核心数据，所以这里只画一条贴着顶部的小条、轻微移动，
     * 绝不遮挡中间的读数区。真正的大弹窗只留给"预警倒计时"和"引爆"两个瞬间。
     */
    public static final int KIND_MELTDOWN_ACTIVE = 3;

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
        return decide(collapsing, detonation, linked, 0.0D);
    }

    /**
     * 同上，外加"熔毁预警倒计时"的值。
     *
     * 【为什么要看 explosionIn，不能只看 collapsing】
     * 作者的时序是：**核心达到熔毁温度 → 先走一段预警倒计时 → 倒计时结束才开始坍缩**
     * （坍缩度从 0 涨起来）。如果只盯 {@code collapsing > 0}，
     * 整段"还剩两分钟"的预警期屏幕上什么都没有 —— 那正是最该警告的时候。
     *
     * @param explosionIn 预警倒计时原始值（&gt;0 表示已在预警期，单位未知，见 {@link #calibrateUnits}）
     */
    public static int decide(double collapsing, boolean detonation, boolean linked, double explosionIn) {
        if (!linked) {
            return KIND_NONE;
        }
        if (detonation) {
            return KIND_DETONATE;
        }
        if (collapsing > 0.0D) {
            // 已经在坍缩：改小条，别挡住核心数据
            return KIND_MELTDOWN_ACTIVE;
        }
        if (explosionIn > 0.0D) {
            return KIND_MELTDOWN;
        }
        return KIND_NONE;
    }

    /**
     * 从两次读数推断"这个量的单位是 tick 还是秒"。
     *
     * 【为什么需要校准】作者代码里 {@code explosionIn} / {@code detonationTimer} 都没有单位注释：
     * 按 tick 算 600 就是 30 秒，按秒算 600 就是 10 分钟，差 20 倍。
     * 但**变化速率**能直接说明问题：每 refresh 掉多少，除以经过的真实秒数，
     * 得到"每秒掉多少单位" —— 约 20 就是 tick 计时，约 1 就是秒计时。
     *
     * @return 每秒包含多少个"单位"（20 = tick，1 = 秒）。数据不足时返回默认 20
     */
    public static double calibrateUnits(double deltaUnits, int intervalTicks) {
        if (deltaUnits <= 0.0D || intervalTicks <= 0) {
            return 20.0D;
        }
        double elapsedSec = intervalTicks * 0.05D;
        if (elapsedSec <= 0.0D) {
            return 20.0D;
        }
        double perSec = deltaUnits / elapsedSec;
        // 20 上下（tick）/ 1 上下（秒）；中间地带默认按 tick 处理
        return perSec > 5.0D ? 20.0D : 1.0D;
    }

    /** 用校准出来的单位把原始值换成秒 */
    public static int secondsFrom(double raw, double unitsPerSecond) {
        if (raw <= 0.0D || unitsPerSecond <= 0.0D) {
            return -1;
        }
        return (int) Math.max(0L, Math.round(raw / unitsPerSecond));
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
