package com.jiezhimod.seal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 完整性自校验 A：
 * 1) 校验 jar 内除 seal 包外全部 *.class 的聚合哈希 H_rest（任何业务类被删改 → 崩溃）
 * 2) 检查 SealB.class 必须存在（SealB 被删除/改名 → 崩溃）
 * SealA 的字节不依赖 SealB 内容，构建时先冻结 SealA，再由 SealB 内容校验 SealA。
 * 仅当从 jar 加载时生效（IDE / 开发目录加载自动跳过）。纯 JDK 依赖。
 */
public class SealA {
    // H_rest ^ 0x5A（真实值由构建脚本注入替换）
    private static final byte[] P_R = new byte[] {
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00
    };

    public static void verify() {
        File jar = locate();
        if (jar == null) {
            return; // 开发模式（目录加载）
        }
        try {
            byte[] hr = hashRest(jar);
            byte[] r = new byte[32];
            for (int i = 0; i < 32; i++) {
                r[i] = (byte) (P_R[i] ^ 0x5A);
            }
            if (!MessageDigest.isEqual(hr, r)) {
                fail();
            }
            // SealB 必须存在（防删除绕过）；hashEntry 抛异常即不存在
            hashEntry(jar, d(new byte[] {
                    (byte) 0x28, (byte) 0x24, (byte) 0x26, (byte) 0x64, (byte) 0x21, (byte) 0x22,
                    (byte) 0x2E, (byte) 0x31, (byte) 0x23, (byte) 0x22, (byte) 0x26, (byte) 0x24,
                    (byte) 0x2F, (byte) 0x64, (byte) 0x38, (byte) 0x2E, (byte) 0x2A, (byte) 0x27,
                    (byte) 0x64, (byte) 0x18, (byte) 0x2E, (byte) 0x2A, (byte) 0x27, (byte) 0x09,
                    (byte) 0x65, (byte) 0x28, (byte) 0x27, (byte) 0x2A, (byte) 0x38, (byte) 0x38
            }));
        } catch (Exception e) {
            fail();
        }
    }

    private static void fail() {
        byte[] m = new byte[] {
                (byte) 0x01, (byte) 0x22, (byte) 0x2E, (byte) 0x31, (byte) 0x23, (byte) 0x22,
                (byte) 0x6B, (byte) 0x26, (byte) 0x24, (byte) 0x2F, (byte) 0x6B, (byte) 0x22,
                (byte) 0x25, (byte) 0x3F, (byte) 0x2E, (byte) 0x2C, (byte) 0x39, (byte) 0x22,
                (byte) 0x3F, (byte) 0x32, (byte) 0x6B, (byte) 0x28, (byte) 0x23, (byte) 0x2E,
                (byte) 0x28, (byte) 0x20, (byte) 0x6B, (byte) 0x2D, (byte) 0x2A, (byte) 0x22,
                (byte) 0x27, (byte) 0x2E, (byte) 0x2F, (byte) 0x65, (byte) 0x6B, (byte) 0x1B,
                (byte) 0x27, (byte) 0x2E, (byte) 0x2A, (byte) 0x38, (byte) 0x2E, (byte) 0x6B,
                (byte) 0x39, (byte) 0x2E, (byte) 0x66, (byte) 0x2F, (byte) 0x24, (byte) 0x3C,
                (byte) 0x25, (byte) 0x27, (byte) 0x24, (byte) 0x2A, (byte) 0x2F, (byte) 0x6B,
                (byte) 0x3F, (byte) 0x23, (byte) 0x2E, (byte) 0x6B, (byte) 0x24, (byte) 0x39,
                (byte) 0x22, (byte) 0x2C, (byte) 0x22, (byte) 0x25, (byte) 0x2A, (byte) 0x27,
                (byte) 0x6B, (byte) 0x21, (byte) 0x2A, (byte) 0x39, (byte) 0x65
        };
        for (int i = 0; i < m.length; i++) {
            m[i] = (byte) (m[i] ^ 0x4B);
        }
        throw new RuntimeException(new String(m, StandardCharsets.UTF_8));
    }

    private static String d(byte[] x) {
        byte[] r = new byte[x.length];
        for (int i = 0; i < x.length; i++) {
            r[i] = (byte) (x[i] ^ 0x4B);
        }
        return new String(r, StandardCharsets.UTF_8);
    }

    private static File locate() {
        try {
            URL loc = SealA.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null || !"file".equals(loc.getProtocol())) {
                return null;
            }
            File f = new File(URLDecoder.decode(loc.getPath(), "UTF-8"));
            if (f.isFile() && f.getName().endsWith(d(new byte[] {(byte) 0x65, (byte) 0x21, (byte) 0x2A, (byte) 0x39}))) {
                return f;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] hashRest(File jar) throws Exception {
        MessageDigest md = MessageDigest.getInstance(d(new byte[] {
                (byte) 0x18, (byte) 0x03, (byte) 0x0A, (byte) 0x66, (byte) 0x79, (byte) 0x7E, (byte) 0x7D
        }));
        String pfx = d(new byte[] {
                (byte) 0x28, (byte) 0x24, (byte) 0x26, (byte) 0x64, (byte) 0x21, (byte) 0x22,
                (byte) 0x2E, (byte) 0x31, (byte) 0x23, (byte) 0x22, (byte) 0x26, (byte) 0x24,
                (byte) 0x2F, (byte) 0x64, (byte) 0x38, (byte) 0x2E, (byte) 0x2A, (byte) 0x27, (byte) 0x64
        });
        String cls = d(new byte[] {(byte) 0x65, (byte) 0x28, (byte) 0x27, (byte) 0x2A, (byte) 0x38, (byte) 0x38});
        TreeMap<String, byte[]> map = new TreeMap<>();
        ZipFile zf = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.endsWith(cls) && !n.startsWith(pfx)) {
                    InputStream in = zf.getInputStream(e);
                    try {
                        map.put(n, readAll(in));
                    } finally {
                        in.close();
                    }
                }
            }
        } finally {
            zf.close();
        }
        for (Map.Entry<String, byte[]> kv : map.entrySet()) {
            md.update(kv.getKey().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(kv.getValue());
        }
        return md.digest();
    }

    private static byte[] hashEntry(File jar, String path) throws Exception {
        MessageDigest md = MessageDigest.getInstance(d(new byte[] {
                (byte) 0x18, (byte) 0x03, (byte) 0x0A, (byte) 0x66, (byte) 0x79, (byte) 0x7E, (byte) 0x7D
        }));
        ZipFile zf = new ZipFile(jar);
        try {
            ZipEntry e = zf.getEntry(path);
            if (e == null) {
                throw new IllegalStateException();
            }
            InputStream in = zf.getInputStream(e);
            try {
                md.update(readAll(in));
            } finally {
                in.close();
            }
        } finally {
            zf.close();
        }
        return md.digest();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
