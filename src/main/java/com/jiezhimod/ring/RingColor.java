package com.jiezhimod.ring;

/**
 * 灯圈颜色的**纯逻辑工具**（不 import 任何 Minecraft 类，离线自测直接跑）。
 *
 * <p>颜色在整条链路上只有一个存储形式：{@code int}，低 24 位 = R | G &lt;&lt; 8 | B &lt;&lt; 16
 * （和 Minecraft 的 {@code 0xRRGGBB} 一致，客户端 tint 直接就能用）。
 * 所有换算 / 插值 / 钳制都收在这里 —— GUI、网络包、方块实体、渲染 tint
 * 四处都要用，散开写必然有一处量纲对不上。</p>
 */
public final class RingColor {

    /** 单个通道的合法范围 0~255 */
    public static final int MAX_CHANNEL = 255;

    private RingColor() {
    }

    /** 打包成 {@code 0xRRGGBB}；越界通道先钳进去（宁可不报错也不要渲染出条纹） */
    public static int pack(int r, int g, int b) {
        return (clampChannel(r) << 16) | (clampChannel(g) << 8) | clampChannel(b);
    }

    /** 单通道钳制（负数 / 越界都夹回 0~255） */
    public static int clampChannel(int v) {
        if (v < 0) {
            return 0;
        }
        return v > MAX_CHANNEL ? MAX_CHANNEL : v;
    }

    public static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    public static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    public static int blue(int rgb) {
        return rgb & 0xFF;
    }

    /**
     * 两色之间按 {@code t}（0~1）线性插值。
     *
     * <p>【为什么按通道插而不是整体一个数】整体插会把"红→蓝"的中间走成发灰的暗色，
     * 逐通道插出来的过渡才是人眼期待的"红→紫→蓝"。</p>
     */
    public static int lerp(int from, int to, double t) {
        if (t <= 0.0D) {
            return from;
        }
        if (t >= 1.0D) {
            return to;
        }
        return pack(
                (int) Math.round(red(from) + (red(to) - red(from)) * t),
                (int) Math.round(green(from) + (green(to) - green(from)) * t),
                (int) Math.round(blue(from) + (blue(to) - blue(from)) * t));
    }

    /** 把颜色压暗到 {@code k}（0~1）：关灯时的"暗色轮廓"就是这么来的 */
    public static int dim(int rgb, double k) {
        if (k <= 0.0D) {
            return 0;
        }
        if (k >= 1.0D) {
            return rgb & 0xFFFFFF;
        }
        return pack((int) Math.round(red(rgb) * k),
                (int) Math.round(green(rgb) * k),
                (int) Math.round(blue(rgb) * k));
    }

    /** 人话格式，日志 / 提示用：#RRGGBB */
    public static String toHex(int rgb) {
        return String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /**
     * 解析 "#RRGGBB" / "RRGGBB" / "R,G,B"；解析失败返回 {@code fallback}。
     * GUI 输入框里玩家手滑多打个空格是常态，这里宽松一点。
     */
    public static int parse(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return fallback;
        }
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.indexOf(',') >= 0) {
            String[] parts = s.split(",");
            if (parts.length != 3) {
                return fallback;
            }
            try {
                return pack(Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        if (s.length() != 6) {
            return fallback;
        }
        try {
            return (int) (Long.parseLong(s, 16) & 0xFFFFFFL);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
