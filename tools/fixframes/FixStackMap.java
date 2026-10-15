import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 用 ASM COMPUTE_FRAMES 强制为 jar 内所有 class 重算 StackMapTable。
 * 解决 reobfJar / ProGuard preverify 缺失导致的 JVM8 VerifyError。
 * 用法: java -cp asm.jar;FixStackMap.jar FixStackMap <in.jar> <out.jar>
 * 调用方必须把 libraryjars（forge mapped / netty / log4j / guava）与 in.jar 加入 -cp，
 * 保证 getCommonSuperClass 能解析类型；解析失败时容错返回 Object。
 */
public class FixStackMap {
    public static void main(String[] args) throws Exception {
        String in = args[0];
        String out = args[1];
        try (ZipFile zin = new ZipFile(in); ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(out))) {
            Enumeration<? extends ZipEntry> en = zin.entries();
            int fixed = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                byte[] data = readAll(zin.getInputStream(e));
                byte[] outData = data;
                if (e.getName().endsWith(".class")) {
                    ClassReader cr = new ClassReader(data);
                    ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
                    try {
                        cr.accept(cw, 0);
                        outData = cw.toByteArray();
                        fixed++;
                    } catch (Throwable t) {
                        // 无法重算的类保持原样，交给后续检查发现
                        System.err.println("SKIP " + e.getName() + ": " + t);
                    }
                }
                ZipEntry ne = new ZipEntry(e.getName());
                zout.putNextEntry(ne);
                zout.write(outData);
                zout.closeEntry();
            }
            System.out.println("fixed " + fixed + " classes");
        }
    }

    static class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader cr, int flags) { super(cr, flags); }

        @Override
        protected String getCommonSuperClass(String t1, String t2) {
            try {
                Class<?> c1 = Class.forName(t1.replace('/', '.'), false, getClass().getClassLoader());
                Class<?> c2 = Class.forName(t2.replace('/', '.'), false, getClass().getClassLoader());
                if (c1.isAssignableFrom(c2)) return t1;
                if (c2.isAssignableFrom(c1)) return t2;
                if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                while (!c1.isAssignableFrom(c2)) {
                    c1 = c1.getSuperclass();
                    if (c1 == null) return "java/lang/Object";
                }
                return c1.getName().replace('.', '/');
            } catch (Throwable t) {
                return "java/lang/Object";
            }
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
