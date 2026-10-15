package com.jiezhimod.seal;

import java.nio.charset.StandardCharsets;

/**
 * 关键字符串解密工具：字符串以 XOR(0x4B) 加密字节存储于调用点，
 * 运行时还原，避免 jar 反编译后直接看到 unlock/gamemode/tp 等关键词。
 * 本类将被混淆器重命名。
 */
public final class Str {
    private Str() {
    }

    public static String d(byte[] x) {
        byte[] r = new byte[x.length];
        for (int i = 0; i < x.length; i++) {
            r[i] = (byte) (x[i] ^ 0x4B);
        }
        return new String(r, StandardCharsets.UTF_8);
    }
}
