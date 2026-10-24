package com.jiezhimod.repeater;

import java.util.Locale;

/**
 * 高级红石中继器的**延迟量与单位换算**（纯逻辑，不 import 任何 MC 类）。
 *
 * 【为什么单独一个类】GUI、方块实体、网络包、离线自测都要用到同一套换算与钳制规则。
 * 散在四处写过一次就一定会有一次不一样（典型的翻车点：GUI 里 1 秒 = 20 tick，
 * 方块实体里手滑写成 10），所以这里只留一份真源。
 *
 * 【单位】对外只有两个单位：
 * <ul>
 *   <li><b>tick</b> —— 游戏刻，1 秒 = 20 tick，也是内部唯一的存储形式；</li>
 *   <li><b>秒</b> —— 显示 / 输入用，保留 2 位小数（1 tick = 0.05 秒）。</li>
 * </ul>
 *
 * 【范围】{@link #MIN_TICKS} ~ {@link #MAX_TICKS}。下限 1 tick 是"最灵敏"，
 * 上限 60 秒 —— 再长就没人会用一个中继器去等，不如改用计时器。
 * **钳制而不是报错**：输入越界时夹到边界，玩家立刻能看到真实生效值。
 */
public final class RepeaterDelay {

    /** 1 秒 = 20 游戏刻 */
    public static final int TICKS_PER_SECOND = 20;

    /** 最短 1 tick（比原版中继器最短的一档还快一档） */
    public static final int MIN_TICKS = 1;

    /** 最长 60 秒 = 1200 tick */
    public static final int MAX_TICKS = 60 * TICKS_PER_SECOND;

    /** 放置时的默认延迟：2 tick（= 0.10 秒，和原版中继器的第 1 档同长） */
    public static final int DEFAULT_TICKS = 2;

    private RepeaterDelay() {
    }

    /** 钳到合法区间；同时把 NaN 之类的脏值挡成默认值 */
    public static int clamp(int ticks) {
        if (ticks < MIN_TICKS) {
            return MIN_TICKS;
        }
        if (ticks > MAX_TICKS) {
            return MAX_TICKS;
        }
        return ticks;
    }

    /** 输入是否落在合法区间（GUI 用它决定"能不能按应用"） */
    public static boolean inRange(int ticks) {
        return ticks >= MIN_TICKS && ticks <= MAX_TICKS;
    }

    /** tick → 秒 */
    public static double toSeconds(int ticks) {
        return ticks / (double) TICKS_PER_SECOND;
    }

    /**
     * 秒 → tick（四舍五入到整数 tick）。
     *
     * 【为什么要四舍五入而不是截断】玩家输入 0.09 秒时，截断会得到 1 tick（0.05 秒），
     * 差得比四舍五入（2 tick = 0.10 秒）更远。四舍五入后"输入 0.09、显示 0.10"至少是最近的刻度。
     */
    public static int fromSeconds(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds)) {
            return DEFAULT_TICKS;
        }
        long ticks = Math.round(seconds * TICKS_PER_SECOND);
        if (ticks < MIN_TICKS) {
            return MIN_TICKS;
        }
        if (ticks > MAX_TICKS) {
            return MAX_TICKS;
        }
        return (int) ticks;
    }

    /** 秒的显示文本：固定 2 位小数（用 ROOT，避免土耳其语等区域把小数点变成逗号） */
    public static String secondsText(int ticks) {
        return String.format(Locale.ROOT, "%.2f", toSeconds(clamp(ticks)));
    }

    /**
     * 解析玩家输入的数字。允许前后空格、全角逗号、末尾的 "s"/"t" 之类装饰。
     *
     * @return 解析失败返回 {@link Double#NaN}
     */
    public static double parse(String raw) {
        if (raw == null) {
            return Double.NaN;
        }
        String s = raw.trim();
        while (s.length() > 0 && (s.endsWith("s") || s.endsWith("S") || s.endsWith("秒")
                || s.endsWith("tick") || s.endsWith("t"))) {
            s = s.substring(0, s.length() - (s.endsWith("tick") ? 4 : 1)).trim();
        }
        s = s.replace(',', '.');
        if (s.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
