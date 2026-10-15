package com.jiezhimod.client.dfc;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.HashMap;
import java.util.Map;

/**
 * 屏幕用的字体：探测一次、缓存住。
 *
 * 【为什么要探测而不是写死】
 * 显示器画面是用 AWT 画到 BufferedImage 上的，字体名写死（比如 "Consolas"）
 * 在没装该字体的机器上会静默退化成一个很难看的默认字体，而且**不会报错**，
 * 排查起来很痛苦。这里按优先级探测，并把"字体是否存在"问一次记下来。
 *
 * 等宽字体用于所有数据和日志（终端感来源），中文用单独一族 ——
 * 等宽英文字体基本都不含汉字，直接拿它画"节枝OS"会变成一堆方块。
 */
public final class ScreenFonts {

    /** 等宽候选：Consolas（Win）-> DejaVu（Linux）-> 逻辑字体兜底 */
    private static final String[] MONO_CANDIDATES = {
            "Consolas", "DejaVu Sans Mono", "Liberation Mono", "Courier New", "Monospaced"
    };
    /** 含中文字形的候选 */
    private static final String[] CJK_CANDIDATES = {
            "Microsoft YaHei", "微软雅黑", "SimHei", "黑体", "SimSun", "Dialog"
    };

    private static String monoFamily = "Monospaced";
    private static String cjkFamily = "Dialog";
    private static boolean probed = false;

    private static final Map<Integer, Font> MONO_CACHE = new HashMap<Integer, Font>();
    private static final Map<Integer, Font> MONO_BOLD_CACHE = new HashMap<Integer, Font>();
    private static final Map<Integer, Font> CJK_CACHE = new HashMap<Integer, Font>();
    private static final Map<Integer, Font> CJK_BOLD_CACHE = new HashMap<Integer, Font>();

    private ScreenFonts() {
    }

    private static void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            java.util.List<String> available = java.util.Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            monoFamily = pick(available, MONO_CANDIDATES, "Monospaced");
            cjkFamily = pickCjk(available);
        } catch (Throwable t) {
            // headless / 无字体环境：退回逻辑字体，AWT 仍能渲染基本 ASCII
            monoFamily = "Monospaced";
            cjkFamily = "Dialog";
        }
    }

    private static String pick(java.util.List<String> available, String[] candidates, String fallback) {
        for (String c : candidates) {
            for (String a : available) {
                if (a.equalsIgnoreCase(c)) {
                    return a;
                }
            }
        }
        return fallback;
    }

    /** 中文族还要求真的能画出汉字，否则等于没选 */
    private static String pickCjk(java.util.List<String> available) {
        for (String c : CJK_CANDIDATES) {
            for (String a : available) {
                if (!a.equalsIgnoreCase(c)) {
                    continue;
                }
                if (new Font(a, Font.PLAIN, 12).canDisplay('\u8282')) {   // '节'
                    return a;
                }
            }
        }
        return "Dialog";
    }

    public static Font mono(int size) {
        probe();
        Font f = MONO_CACHE.get(size);
        if (f == null) {
            f = new Font(monoFamily, Font.PLAIN, size);
            MONO_CACHE.put(size, f);
        }
        return f;
    }

    public static Font monoBold(int size) {
        probe();
        Font f = MONO_BOLD_CACHE.get(size);
        if (f == null) {
            f = new Font(monoFamily, Font.BOLD, size);
            MONO_BOLD_CACHE.put(size, f);
        }
        return f;
    }

    /**
     * 能画汉字的字体（正文标签用，常规字重）。
     *
     * 小字号下 BOLD 的中文会糊成一坨（笔画粘连），标签一律用常规字重；
     * 只有大标题才用 {@link #cjkBold(int)}。
     */
    public static Font cjk(int size) {
        probe();
        Font f = CJK_CACHE.get(size);
        if (f == null) {
            f = new Font(cjkFamily, Font.PLAIN, size);
            CJK_CACHE.put(size, f);
        }
        return f;
    }

    /** 能画汉字的字体，粗体（大标题、需要强调的数值） */
    public static Font cjkBold(int size) {
        probe();
        Font f = CJK_BOLD_CACHE.get(size);
        if (f == null) {
            f = new Font(cjkFamily, Font.BOLD, size);
            CJK_BOLD_CACHE.put(size, f);
        }
        return f;
    }
}
