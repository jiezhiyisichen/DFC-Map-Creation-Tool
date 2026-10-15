package com.jiezhimod.client.anim;

/**
 * 聊天栏 / 通知的动画数学（纯函数，无 Minecraft 依赖，方便离线自测）。
 *
 * 三段式生命周期：
 *   进入 ENTER_MS  ->  保持 HOLD_MS  ->  淡出 FADE_MS（同时向左滑出）-> 移除
 * 另外，聊天面板被打开时额外有一段"从左向右滑入"的进场。
 */
public final class AnimMath {

    private AnimMath() {
    }

    public static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }

    public static float easeOutCubic(float p) {
        p = clamp01(p);
        float t = 1.0F - p;
        return 1.0F - t * t * t;
    }

    public static float easeOutQuart(float p) {
        p = clamp01(p);
        float t = 1.0F - p;
        return 1.0F - t * t * t * t;
    }

    public static float easeInQuad(float p) {
        p = clamp01(p);
        return p * p;
    }

    /** 缓出带回弹（尾部会短暂越过 1.0 再收回），用于"弹起"手感 */
    public static float easeOutBack(float p) {
        p = clamp01(p);
        float c1 = 1.70158F;
        float c3 = c1 + 1.0F;
        float t = p - 1.0F;
        return 1.0F + c3 * t * t * t + c1 * t * t;
    }

    /** 进入动画进度：0 -> 1，超过时长恒为 1 */
    public static float enterProgress(long ageMs, long enterMs) {
        if (enterMs <= 0L) {
            return 1.0F;
        }
        return clamp01((float) ageMs / (float) enterMs);
    }

    /**
     * 起点残差：1 = 还停在起始位置，0 = 已就位。
     * 因为用了 easeOutBack，中段会短暂为负（越过终点再回弹）。
     */
    public static float enterResidual(long ageMs, long enterMs) {
        return 1.0F - easeOutBack(enterProgress(ageMs, enterMs));
    }

    /** 进入期的不透明度（越大越实），用于淡入 */
    public static float enterAlpha(long ageMs, long enterMs) {
        return easeOutQuart(enterProgress(ageMs, enterMs));
    }

    /** 距保持期结束还有多久没到之前恒为 0；完全淡出时为 1 */
    public static float fadeProgress(long ageMs, long holdMs, long fadeMs) {
        if (ageMs <= holdMs) {
            return 0.0F;
        }
        if (fadeMs <= 0L) {
            return 1.0F;
        }
        return clamp01((float) (ageMs - holdMs) / (float) fadeMs);
    }

    /** 淡出期剩余不透明度 1 -> 0 */
    public static float fadeAlpha(long ageMs, long holdMs, long fadeMs) {
        return 1.0F - fadeProgress(ageMs, holdMs, fadeMs);
    }

    public static boolean isGone(long ageMs, long holdMs, long fadeMs) {
        return ageMs >= holdMs + fadeMs;
    }

    /**
     * 淡出期的横向滑出偏移（聊天栏专用：向左）。
     *
     * 这里保留两参重载是为了让调用点读起来有方向感 —— JiezhiChatGui 贴着屏幕左边，
     * 向左退场；右下角的通知要用 {@link #slideOutOffset(long, long, long, float, boolean)}。
     */
    public static float slideOutOffset(long ageMs, long holdMs, long fadeMs, float distance) {
        return -easeInQuad(fadeProgress(ageMs, holdMs, fadeMs)) * distance;
    }

    /**
     * 淡出期的横向滑出偏移，**可指定方向**。
     *
     * 为什么要有这个重载：1.12.2 的聊天栏需要"向左退出"，
     * 而右下角的通知需要"向右侧弹出"（它是靠右边缘的，向左滑会横穿屏幕中间，
     * 视觉上很怪；向右则像是被"收"回屏幕外，更符合通知的临时性）。
     *
     * @param right true = 向右滑出（正值），false = 向左滑出（负值）
     */
    public static float slideOutOffset(long ageMs, long holdMs, long fadeMs,
                                       float distance, boolean right) {
        float t = easeInQuad(fadeProgress(ageMs, holdMs, fadeMs)) * distance;
        return right ? t : -t;
    }

    /**
     * 面板刚打开时，每行从左往右滑入的横向偏移（负值）。
     * @param sinceOpenMs 打开后经过的毫秒
     * @param rowIndex    该行在可见窗口中的序号（0 = 最靠上的那行，先滑入）
     * @param durationMs  单行滑入时长
     * @param staggerMs   逐行错开
     * @param distance    起始偏移量
     */
    public static float slideInOffset(long sinceOpenMs, int rowIndex, long durationMs, long staggerMs, float distance) {
        long local = sinceOpenMs - (long) rowIndex * staggerMs;
        if (local <= 0L) {
            return -distance;
        }
        if (durationMs <= 0L) {
            return 0.0F;
        }
        return -distance * (1.0F - easeOutCubic((float) local / (float) durationMs));
    }
}
