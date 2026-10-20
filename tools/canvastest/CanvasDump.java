import com.jiezhimod.client.dfc.ScreenCanvas;
import com.jiezhimod.dfc.DfcSnapshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 离屏复现 ScreenCanvas 的绘制，导出 PNG。
 * 不碰任何 Minecraft / GL 类：反射取出 AWT 的 image 字段即可。
 */
public class CanvasDump {

    public static void main(String[] args) throws Exception {
        System.out.println("TEX_W x TEX_H = " + ScreenCanvas.TEX_W + " x " + ScreenCanvas.TEX_H);

        DfcSnapshot snap = realistic();
        List<String> ev = new ArrayList<String>();
        ev.add("已连接 暗核聚变堆核心");
        ev.add("堆芯上线 结构12件");
        ev.add("! 温度升高 812°C");
        ev.add("稳定器 4 -> 5 台");

        // 三个画面：关机 / 开机 / 主界面
        dump("off",     -1L,     false, snap, ev, ScreenCanvas.PAGE_CORE);
        dump("boot",    600L,    true,  snap, ev, ScreenCanvas.PAGE_CORE);
        dump("console", 2000L,   true,  snap, ev, ScreenCanvas.PAGE_CORE);
        dump("main",    999999L, true,  snap, ev, ScreenCanvas.PAGE_CORE);
        dumpKeypad();
        dumpBanner();

        // 单独测空数据快照（未绑定数据源时的样子）：两页都要看
        dump("main_nolink", 999999L, true, DfcSnapshot.empty(), ev, ScreenCanvas.PAGE_CORE);
        dump("components_nolink", 999999L, true, DfcSnapshot.empty(), ev,
                ScreenCanvas.PAGE_COMPONENTS);

        // 第二块显示器（部件监视页）
        dump("components", 999999L, true, snap, ev, ScreenCanvas.PAGE_COMPONENTS);

        // 崩溃告警：collapsing 只要 > 0 就该整屏转暗红 + 盘心立刻变黄 + 顶部闪烁 !!
        DfcSnapshot onset = DfcSnapshot.copied(snap);
        onset.collapsing = 0.05D;
        onset.explosionIn = 114.0D;
        dump("main_collapse_start", 999999L, true, onset, ev, ScreenCanvas.PAGE_CORE);

        DfcSnapshot alert = DfcSnapshot.copied(snap);
        alert.collapsing = 0.34D;
        alert.explosionIn = 79.0D;
        dump("main_alert", 999999L, true, alert, ev, ScreenCanvas.PAGE_CORE);

        // collapsing >= 0.5 时盘心应由黄转红
        DfcSnapshot critical = DfcSnapshot.copied(snap);
        critical.collapsing = 0.72D;
        critical.explosionIn = 34.0D;
        dump("main_critical", 999999L, true, critical, ev, ScreenCanvas.PAGE_CORE);

        // 稳定度对比：盘顶那行读的就是它。掉到 42% 应转红（危急），98% 保持绿。
        // 两张图连起来能看出这一行确实在跟着数据走，不是画死的。
        DfcSnapshot sLow = DfcSnapshot.copied(snap);
        sLow.stabilization = 4.2D;
        dump("main_stab_low", 999999L, true, sLow, ev, ScreenCanvas.PAGE_CORE);
        DfcSnapshot sHigh = DfcSnapshot.copied(snap);
        sHigh.stabilization = 9.8D;
        dump("main_stab_full", 999999L, true, sHigh, ev, ScreenCanvas.PAGE_CORE);

        // 闪烁相位对比：同一告警快照、两个相差半个周期的时刻，!! 必须一亮一灭
        // （半周期 = ALERT_BLINK_MS/2 = 800/2 = 400ms，这里错开 400ms 取两帧）
        dump("alert_blink_on", 1000000L, true, alert, ev, ScreenCanvas.PAGE_CORE);
        dump("alert_blink_off", 1000400L, true, alert, ev, ScreenCanvas.PAGE_CORE);

        // 【熔毁报错页】坍缩度刚离开 0 的那一瞬整屏改画 301，4 秒后自动重启。
        // 两张图错开 3.4 秒：重启倒计时和进度条必须真的在走，不是画死的。
        dump("fault_start", 999999L, true, alert, ev, ScreenCanvas.PAGE_CORE, 200L);
        dump("fault_near", 999999L, true, alert, ev, ScreenCanvas.PAGE_CORE, 3600L);

        // 离线态：反应堆温度不到 100°C —— 整屏改画"离线"页，不许出现任何红色告警元素
        DfcSnapshot cold = DfcSnapshot.copied(snap);
        cold.temperature = 12.5D;
        cold.stabilization = 0.0D;
        cold.collapsing = 0.0D;
        cold.containedEnergy = 0.0D;
        dump("main_offline", 999999L, true, cold, ev, ScreenCanvas.PAGE_CORE);
        // 门槛正下方 / 正上方各来一张，肉眼确认 100°C 这条线画在哪
        cold.temperature = 99.99D;
        dump("main_offline_edge", 999999L, true, cold, ev, ScreenCanvas.PAGE_CORE);
        cold.temperature = 100.0D;
        dump("main_online_edge", 999999L, true, cold, ev, ScreenCanvas.PAGE_CORE);

        // 第三块显示器（自定义数据列表）：8 条塞满，含一格"设备被拆 -> 离线"
        // 流体名这里直接给**客户端翻译后的成品**（"柴油"）。服务端发的是语言键
        // "hbmfluid.diesel"，客户端 TileDfcScreen.applyServerState 里翻一次；
        // 离屏导出没有语言管理器，所以这里给翻译结果，看到的才是玩家真正看到的版式。
        DfcSnapshot custom = DfcSnapshot.copied(snap);
        custom.watches.add(watch("储液罐", "柴油", "12.50 kmB / 24.00 kmB", 0.52D, true));
        custom.watches.add(watch("储液罐", "水", "1.20 kmB / 16.00 kmB", 0.075D, true));
        custom.watches.add(watch("电池", "储能", "500.00 MHE / 1.00 GHE", 0.50D, true));
        custom.watches.add(watch("电池", "储能", "12.00 MHE / 250.00 MHE", 0.048D, true));
        custom.watches.add(watch("储罐", "蒸汽", "0.00 mB / 8.00 kmB", 0.0D, true));
        custom.watches.add(watch("储液罐", "煤油", "7.30 kmB / 16.00 kmB", 0.456D, true));
        custom.watches.add(watch("电池", "储能", "--", -1.0D, false));   // 设备被拆 -> 显示"离线"
        custom.watches.add(watch("储液罐", "重油", "24.00 kmB / 24.00 kmB", 1.0D, true));
        dump("custom", 999999L, true, custom, ev, ScreenCanvas.PAGE_CUSTOM);

        // 一条都没有时的引导文案
        dump("custom_empty", 999999L, true, DfcSnapshot.empty(), ev, ScreenCanvas.PAGE_CUSTOM);

        // 日志折行：故意塞几条**长到放不下**的中文句子，确认它们折成多行而不是被 ".." 截断
        List<String> longEv = new ArrayList<String>();
        longEv.add("已连接 暗核聚变堆核心");
        longEv.add("! 消耗速率 5.00 mB/t 偏离额定值");
        longEv.add("! 稳定度下降 由 100.00% 降到 87.30%");
        longEv.add("堆芯上线 结构12件 稳定器4台 透镜OMEGA");
        longEv.add("## 堆芯崩溃 85.00% 立即撤离");
        dump("main_log_wrap", 999999L, true, snap, longEv, ScreenCanvas.PAGE_CORE);

        // 折行逻辑的直测（不靠眼睛）：wrap() 出来的每一段都必须放得下、且拼回去等于原文
        wrapChecks();
    }

    /**
     * 直接对 {@code ScreenCanvas.wrap()} 做断言。
     *
     * 【为什么要单独测】折行出错的两种表现 —— "没折、字被挤出面板" 与
     * "折得太碎、一句话占满整屏" —— 在 PNG 上都只是"看着不太对"，
     * 靠肉眼很难判断是否真的每一段都没超宽。这里用反射把每段量一遍。
     */
    private static void wrapChecks() throws Exception {
        ScreenCanvas c = new ScreenCanvas();
        // 先渲染一次，让 g / 字体上下文就绪（wrap 需要 FontMetrics）
        c.render(999999L, true, DfcSnapshot.empty(), new ArrayList<String>(), ScreenCanvas.PAGE_CORE);

        java.lang.reflect.Method m = ScreenCanvas.class.getDeclaredMethod(
                "wrap", String.class, int.class, java.awt.Font.class);
        m.setAccessible(true);
        java.lang.reflect.Method twm = ScreenCanvas.class.getDeclaredMethod(
                "textWidthLog", String.class, java.awt.Font.class);
        twm.setAccessible(true);

        int maxW = 79;                                  // 右栏 88 - 9
        java.awt.Font f = (java.awt.Font) callFont(c, "fCjk", 9);
        String[] cases = {
                "短",
                "已连接 暗核聚变堆核心",
                "! 消耗速率 5.00 mB/t 偏离额定值",
                "! 稳定度下降 由 100.00% 降到 87.30%",
                "## 堆芯崩溃 85.00% 立即撤离",
                "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789",
        };
        int bad = 0, joinedBad = 0, emptyBad = 0;
        for (String s : cases) {
            @SuppressWarnings("unchecked")
            List<String> parts = (List<String>) m.invoke(c, s, maxW, f);
            if (parts.isEmpty()) {
                emptyBad++;
            }
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if ((int) twm.invoke(c, p, f) > maxW) {
                    bad++;                              // 有一段还是超宽 -> 折行失效
                }
                sb.append(p);
            }
            // 拼回去（忽略断点处丢掉的空格）应当与原串一致
            if (!sb.toString().replace(" ", "").equals(s.replace(" ", ""))) {
                joinedBad++;
            }
        }
        System.out.println("WRAP CHECK: overflow=" + bad + " lostChars=" + joinedBad
                + " empty=" + emptyBad);
        System.out.println(bad == 0 && joinedBad == 0 && emptyBad == 0
                ? "WRAP OK" : "WRAP FAIL");
    }

    /** 反射调 ScreenCanvas 上的字体助手（fCjk(int)） */
    private static Object callFont(ScreenCanvas c, String name, int size) throws Exception {
        java.lang.reflect.Method mm = ScreenCanvas.class.getDeclaredMethod(name, int.class);
        mm.setAccessible(true);
        return mm.invoke(c, size);
    }

    /** 造一条数据槽（读数直接给成品文本，模拟服务端算完发过来的样子） */
    private static com.jiezhimod.dfc.DfcWatch watch(String device, String name, String value,
                                                    double ratio, boolean alive) {
        com.jiezhimod.dfc.DfcWatch w = new com.jiezhimod.dfc.DfcWatch(
                0, 64, 0, 0, com.jiezhimod.dfc.DfcWatch.KIND_FLUID, 0);
        w.device = device;
        w.name = name;
        w.value = value;
        w.ratio = ratio;
        w.alive = alive;
        return w;
    }

    private static void dump(String tag, long ms, boolean powered,
                             DfcSnapshot snap, List<String> ev, int page) throws Exception {
        dump(tag, ms, powered, snap, ev, page, -1L);
    }

    /** 同上，外加熔毁报错页（faultElapsedMs &lt; 0 = 没有报错页） */
    private static void dump(String tag, long ms, boolean powered,
                             DfcSnapshot snap, List<String> ev, int page, long faultElapsedMs)
            throws Exception {
        ScreenCanvas c = new ScreenCanvas();
        c.render(ms, powered, snap, ev, page, faultElapsedMs);

        Field fi = ScreenCanvas.class.getDeclaredField("image");
        fi.setAccessible(true);
        BufferedImage img = (BufferedImage) fi.get(c);

        Field fp = ScreenCanvas.class.getDeclaredField("pixels");
        fp.setAccessible(true);
        int[] px = (int[]) fp.get(c);

        // 统计非黑像素
        int nonBlack = 0, opaque = 0, maxLum = 0;
        for (int p : px) {
            int a = (p >>> 24) & 0xFF;
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            if (a != 0) {
                opaque++;
            }
            int lum = Math.max(r, Math.max(g, b));
            if (lum > 8) {
                nonBlack++;
            }
            if (lum > maxLum) {
                maxLum = lum;
            }
        }
        int total = px.length;
        System.out.printf("[%s] opaque=%d/%d (%.1f%%)  nonBlack(lum>8)=%d (%.2f%%)  maxLum=%d%n",
                tag, opaque, total, 100.0 * opaque / total,
                nonBlack, 100.0 * nonBlack / total, maxLum);

        // 导出 PNG（放到可读目录）
        String out = System.getProperty("dumpDir", ".");
        File f = new File(out, "dfc_canvas_" + tag + ".png");
        BufferedImage opaqueImg = new BufferedImage(ScreenCanvas.TEX_W, ScreenCanvas.TEX_H,
                BufferedImage.TYPE_INT_RGB);
        opaqueImg.setRGB(0, 0, ScreenCanvas.TEX_W, ScreenCanvas.TEX_H, px, 0, ScreenCanvas.TEX_W);
        ImageIO.write(opaqueImg, "png", f);
        System.out.println("      -> " + f.getAbsolutePath());

        // 核心页圆盘区单独放大导出：这一块的"字压图形"问题在整屏图上太小看不清，
        // 放大 2 倍后一眼能判断数字有没有盖住刻度环 / 指针。
        if (page == ScreenCanvas.PAGE_CORE) {
            File cf = new File(out, "dfc_dial_" + tag + ".png");
            ImageIO.write(crop(px, 86, 19, 164, 108, 2), "png", cf);
        }
    }

    /**
     * 导出密码盘面板。
     *
     * 密码盘的世界渲染是 TESR + 动态纹理，离屏跑不了 GL；
     * 但"面板长什么样"完全是 ScreenCanvas 画的 AWT 画布 —— 这一层可以脱离游戏导出，
     * 用来检查键位有没有出框、便签上的字会不会压线、锁定态颜色对不对。
     */
    private static void dumpKeypad() throws Exception {
        ScreenCanvas c = new ScreenCanvas();
        // 常态：输入了 3 位
        c.renderKeypad("775291", "775", 6, false, false, false, 0L);
        writeKeypad(c, "keypad_normal");

        // 输错：键盘描边转红
        c = new ScreenCanvas();
        c.renderKeypad("775291", "", 6, false, false, true, 0L);
        writeKeypad(c, "keypad_reject");

        // 已锁定
        c = new ScreenCanvas();
        c.renderKeypad("775291", "", 6, true, false, false, 0L);
        writeKeypad(c, "keypad_locked");

        // 已通过
        c = new ScreenCanvas();
        c.renderKeypad("775291", "", 6, false, true, false, 0L);
        writeKeypad(c, "keypad_accepted");
    }

    private static void writeKeypad(ScreenCanvas c, String tag) throws Exception {
        Field fp = ScreenCanvas.class.getDeclaredField("pixels");
        fp.setAccessible(true);
        int[] px = (int[]) fp.get(c);

        int nonBlack = 0;
        for (int p : px) {
            int lum = Math.max((p >> 16) & 0xFF, Math.max((p >> 8) & 0xFF, p & 0xFF));
            if (lum > 8) {
                nonBlack++;
            }
        }
        System.out.printf("[%s] nonBlack=%.2f%%%n", tag, 100.0 * nonBlack / px.length);

        String out = System.getProperty("dumpDir", ".");
        BufferedImage img = new BufferedImage(ScreenCanvas.TEX_W, ScreenCanvas.TEX_H,
                BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, ScreenCanvas.TEX_W, ScreenCanvas.TEX_H, px, 0, ScreenCanvas.TEX_W);
        File f = new File(out, "dfc_canvas_" + tag + ".png");
        ImageIO.write(img, "png", f);
        System.out.println("      -> " + f.getAbsolutePath());
    }

    /**
     * 导出覆盖弹窗（熔毁 / 引爆）。
     *
     * 弹窗是纯 AWT 画的（红框、斜纹、箭头、倒计时），可以脱离 GL 导出。
     * 这里故意"渲染两次"：第一次只用来启动弹入动画，睡 220ms 后再渲染 ——
     * 此时 alpha 已到 1，导出的就是"弹到位"的样子。
     */
    private static void dumpBanner() throws Exception {
        java.util.List<String> ev = new java.util.ArrayList<String>();

        // 熔毁：坍缩度 0.35 -> 剩余 (1-0.35)*120 = 78 秒 = 1:18
        ScreenCanvas c = new ScreenCanvas();
        DfcSnapshot melt = new DfcSnapshot();
        melt.linked = true;
        melt.collapsing = 0.35D;
        c.render(999999L, true, melt, ev, ScreenCanvas.PAGE_CORE);
        Thread.sleep(220L);
        c.render(1000300L, true, melt, ev, ScreenCanvas.PAGE_CORE);
        writeKeypad(c, "banner_meltdown");

        // 引爆器：坍缩度 0.5 -> 60 秒 = 1:00
        ScreenCanvas c2 = new ScreenCanvas();
        DfcSnapshot det = new DfcSnapshot();
        det.linked = true;
        det.detonation = true;
        det.collapsing = 0.5D;
        c2.render(999999L, true, det, ev, ScreenCanvas.PAGE_CORE);
        Thread.sleep(220L);
        c2.render(1000300L, true, det, ev, ScreenCanvas.PAGE_CORE);
        writeKeypad(c2, "banner_detonate");

        // 熔毁进行中：只画顶部小条（不挡核心数据）
        ScreenCanvas c3 = new ScreenCanvas();
        DfcSnapshot active = new DfcSnapshot();
        active.linked = true;
        active.collapsing = 0.55D;
        active.secondsToExplosion = 54.0F;
        c3.render(999999L, true, active, ev, ScreenCanvas.PAGE_CORE);
        Thread.sleep(220L);
        c3.render(1000300L, true, active, ev, ScreenCanvas.PAGE_CORE);
        writeKeypad(c3, "banner_active_strip");
    }

    /** 从 RGBA 像素数组里裁一块（逻辑坐标，内部 ×SS）并做整数倍最近邻放大 */
    private static BufferedImage crop(int[] px, int lx0, int ly0, int lx1, int ly1, int scale) {
        int ss = ScreenCanvas.TEX_W / 256;
        int x0 = lx0 * ss, y0 = ly0 * ss, x1 = lx1 * ss, y1 = ly1 * ss;
        int w = x1 - x0, h = y1 - y0;
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h * scale; y++) {
            for (int x = 0; x < w * scale; x++) {
                int sx = x0 + x / scale, sy = y0 + y / scale;
                img.setRGB(x, y, px[sy * ScreenCanvas.TEX_W + sx]);
            }
        }
        return img;
    }

    private static DfcSnapshot realistic() {
        DfcSnapshot d = DfcSnapshot.empty();
        d.linked = true;
        d.sourceKind = DfcSnapshot.SOURCE_CORE;
        d.hasCore = true;
        d.temperature = 812.5D;
        d.meltingPoint = 6000;
        // 这两个量纲都是实测口径：
        //  stabilization 是各稳定器累加值，满标度 10（作者的进度条以 10 为满格）
        //  collapsing 是 0~1 的熔毁进度
        d.stabilization = 10.0D;
        // 正常工况：不崩溃（collapsing = 0）—— 告警态由 main_collapse_start / main_alert 单独构造，
        // 否则"正常主界面"这张基线图会自带红色主题，等于没验证过正常长什么样。
        d.collapsing = 0.0D;
        // 核心能量：原始值口径，×1000 才是 SPK（见 DfcFormat.SPK_SCALE）。
        // 取 5.0e4 -> "50.00 MSPK"，正好落在满刻度（CORE_ENERGY_BAR_SCALE = 1e5）的一半，
        // 进度条一眼看得出"半格"，不至于顶格或全空。
        d.containedEnergy = 5.0e4D;
        d.maxEnergy = 1.0e4D;
        d.gainedEnergy = 5.0e4D;
        d.expellingEnergy = 3.4e3D;
        d.incomingSpk = 5.5e3D;
        // 潜在增益是 1 起步的倍率（圆盘指针读数），不是能量
        d.potentialGain = 3.5D;
        d.ticks = 12_345_678;
        d.stabilizers = 5;
        d.lastStabilizers = 4;
        d.componentCount = 12;
        d.stabPresent = true;
        d.stabActive = true;
        d.stabLevel = 2;
        d.stabPower = 8_000_000_000L;
        d.lensType = "PYROCLASTIC";
        // lensHealth 是 0~100 的百分数（和游戏内控制面板一致），不是 0~1 比值
        d.lensHealth = 64.0F;
        d.tempDelta = 2.5D;

        // ---- 第二页（部件监视）：夹具要能画出"有货"的样子 ----
        // 发射器/接收器/喷射器都是聚合量，台数取互不相同的数，方便一眼看出有没有串栏
        d.emitterCount = 7;
        d.emitterActiveCount = 5;
        d.emitterWatts = 320;
        d.emitterSpk = 6.8e6D;
        d.receiverCount = 12;
        d.receiverJoules = 4.12e9D;
        d.injectorCount = 3;
        d.injectorFuel = 184_000.0D;
        // 爆发图的两行读的是 expellingEnergy（核心吐给接收器的速率），夹具里要给非 0 值，
        // 否则离屏图上只能看到 "0 SPK"，等于没验证这两行
        d.expellingSpk = 3.4e3D;
        // 流体名用真实的 stringId（不是本地化名），罐A 接近满、罐B 快见底（<15% 应转黄）
        d.fuelTypeA = "DFC FUEL";
        d.fuelRatioA = 0.83D;
        d.fuelTypeB = "DHC";
        d.fuelRatioB = 0.07D;
        return d;
    }
}
