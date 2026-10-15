package com.jiezhimod.countdown;

/**
 * 倒计时数据（服务端与客户端共用）。
 *
 * style:
 *   1 = 普通时间 + 横线（rgb）
 *   2 = 斜杠包裹动画 \\\时间///（固定红）
 *   3 = 框框圈住时间（rgb）
 *   4 = 高精度 mm:ss.t（十分之一秒，rgb；最后 10 秒小数位转黄、3 秒转红）
 *   5 = !mm:ss.t!（感叹号包裹，最后 5 秒感叹号脉冲、3 秒转红）
 *   6 = mm:ss.t + 下方进度条（条随剩余时间收缩，临近时由 rgb 转黄转红）
 */
public class Countdown {
    public static final int MIN_STYLE = 1;
    public static final int MAX_STYLE = 6;

    public final String name;
    public final int style;
    public final float[] rgb = new float[3];   // 样式1、3、4、5、6 的颜色（0-1），样式2 固定红色
    public long remainingTicks;                 // 剩余 tick
    /** 初始总 tick（用于样式6 进度条的分母）；需随同步包传输，否则客户端每秒重建后会丢失 */
    public long totalTicks;
    public boolean paused;                      // 是否暂停
    /** 是否已归零结束；结束后保留在列表中以便 /countdown_test 检测 0，但不再递减 */
    public boolean finished;

    public Countdown(String name, int style, float r, float g, float b, long remainingTicks) {
        this.name = name;
        this.style = style;
        this.rgb[0] = r;
        this.rgb[1] = g;
        this.rgb[2] = b;
        this.remainingTicks = remainingTicks;
        this.totalTicks = Math.max(1L, remainingTicks);
        this.finished = remainingTicks <= 0;
    }

    public static boolean isValidStyle(int style) {
        return style >= MIN_STYLE && style <= MAX_STYLE;
    }

    /** 是否需要十分之一秒精度（渲染时用 partialTicks 做帧间平滑，避免数字每 tick 硬跳） */
    public static boolean isPreciseStyle(int style) {
        return style == 4 || style == 5 || style == 6;
    }

    /** 指令回显用的样式说明（面向玩家的提示一律英文） */
    public static String styleDescription(int style) {
        switch (style) {
            case 1:
                return "plain + underline";
            case 2:
                return "slash animation (fixed red)";
            case 3:
                return "boxed";
            case 4:
                return "precise mm:ss.t";
            case 5:
                return "precise + exclamation marks";
            case 6:
                return "precise + progress bar";
            default:
                return "unknown";
        }
    }
}
