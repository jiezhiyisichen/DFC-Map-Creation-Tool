package com.jiezhimod.util;

import java.util.regex.Pattern;

/**
 * 文本小工具。
 *
 * 1.12.2 的聊天框/命令方块无法输入真正的分节号 §，
 * 所以约定用字面 "\u00a7"（六个 ASCII 字符）代替 —— 与 /sub 指令一致的做法，
 * 在服务端收到参数后统一解码成真正的 §，客户端渲染时才认颜色码。
 */
public final class ColorUtil {

    /** 待替换的字面转义：\u00a7 六个字符 */
    private static final String ESCAPED = "\\u00a7";
    private static final String REAL = "§";
    private static final Pattern CODE = Pattern.compile("§[0-9A-Fa-fK-Ok-oRr]");

    private ColorUtil() {
    }

    /** 把字面 \u00a7 还原成真正的分节号 */
    public static String decode(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace(ESCAPED, REAL);
    }

    /** 去掉所有颜色/格式码，用于回显（避免把 § 原样打进聊天栏） */
    public static String stripCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return CODE.matcher(decode(text)).replaceAll("");
    }
}
