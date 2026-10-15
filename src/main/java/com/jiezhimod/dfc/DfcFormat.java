package com.jiezhimod.dfc;

/**
 * DFC 数据的显示格式化（纯函数，无 Minecraft 依赖，便于离线自测）。
 *
 * 参照诅咒版 CoreGUI 的做法：数值一律带 SI 前缀，温度带 K，时间带方括号。
 * 保持纯函数是因为这些是"数值 -> 文案"的映射，最容易出错也最值得断言。
 *
 * 【统一保留小数点后 2 位】需求要求"所有数据显示小数点后 2 位"。
 * 所以温度 / SI 数值 / 百分比 / 变化率四条路径全部走 {@code %.2f}，
 * **唯一例外是时间类**（{@link #duration} 的 [H:MM:SS] 与 {@link #countdown}
 * 的 mm:ss）—— 秒后面挂两位小数（[0:04:29.00]）不是"精度更高"，
 * 是把"时刻"写成了"测量值"，反而更难读。
 */
public final class DfcFormat {

    private DfcFormat() {
    }

    /** SI 前缀（与诅咒版 SIPfx 的量级一致：k / M / G / T / P） */
    private static final String[] SI_PREFIX = {"", "k", "M", "G", "T", "P", "E"};

    /**
     * 温度显示。
     *
     * 【单位：数据源本身就是摄氏度，不要再做 K→°C 换算】
     * 作者自己的 CoreGUI 是 {@code String.format("%01.1f", getDFCTemperature()) + "°C"}，
     * 全程没有任何 ±273.15；同一界面里的 {@code getDFCMeltingPoint()}（默认 6000）
     * 也直接当 °C 用（"Temperature: 262.2°C / 6000"）。
     * 早期版本按"HBM 内部是开尔文"的直觉减了 273.15，结果面板显示 -11°C、
     * 而游戏里本体显示 262.2°C —— 差一条 273 的假数据，比不显示更糟。
     *
     * 超过 10000 自动升 k°C / M°C，避免一位数字撑满整行。
     */
    public static String temperature(double celsius) {
        double v = celsius;
        int idx = 0;
        while (Math.abs(v) >= 10000.0D && idx < SI_PREFIX.length - 1) {
            v /= 1000.0D;
            idx++;
        }
        return String.format(java.util.Locale.ROOT, "%.2f%s°C", v, SI_PREFIX[idx]);
    }

    /** 带 SI 前缀的裸数值（能量、SPK、液量都用它），保留 2 位小数 */
    public static String si(double value, String unit) {
        double v = value;
        int idx = 0;
        while (Math.abs(v) >= 1000.0D && idx < SI_PREFIX.length - 1) {
            v /= 1000.0D;
            idx++;
        }
        return String.format(java.util.Locale.ROOT, "%.2f %s%s", v, SI_PREFIX[idx], unit);
    }

    /** 能量：HBM 的 HE 单位 */
    public static String energy(double he) {
        return si(he, "HE");
    }

    /** 液量：HBM 的 mB（毫桶） */
    public static String amount(double milliBuckets) {
        return si(milliBuckets, "mB");
    }

    /**
     * 能量爆发（getDFCExpellingEnergy）—— 核心最近 1 秒里吐给接收器的能量之和。
     *
     * 作者自己的 CoreGUI 就是这么标的（{@code "Expelling Energy: " + v/1000 + "MSPK/s"}），
     * 只是这里改用 SPK + 自动 SI 前缀，免得 "250000.000 MSPK/s" 这种长串撞出面板。
     */
    public static String burst(double expellingEnergy) {
        return si(expellingEnergy, "SPK") + "/s";
    }

    /** 每秒变化量，带正负号（参考图里的 "+2/s"），保留 2 位小数 */
    public static String rate(double perTick) {
        double perSecond = perTick * 20.0D;
        double abs = Math.abs(perSecond);
        if (abs < 0.005D) {
            return "0.00/s";
        }
        if (abs >= 1000.0D) {
            String sign = perSecond >= 0.0D ? "+" : "";
            return sign + si(perSecond, "/s");
        }
        return String.format(java.util.Locale.ROOT, "%+.2f/s", perSecond);
    }

    /**
     * 百分比：入参是 **0~1 的比值**，输出 0.00% ~ 100.00%。
     * 两端直接夹住 —— 界面上的百分比不该出现 999% 这种数。
     * （稳定度那种非比值的量请走 {@link #stabilization}。）
     */
    public static String percent(double ratio01) {
        double p = ratio01 * 100.0D;
        if (p < 0.0D) {
            p = 0.0D;
        }
        if (p > 100.0D) {
            p = 100.0D;
        }
        return String.format(java.util.Locale.ROOT, "%.2f%%", p);
    }

    /** 百分比，已经就是 0~100 的数值 */
    public static String percentRaw(double value100) {
        return percent(value100 / 100.0D);
    }

    // ==================== 稳定度（非 0~1 比值，需先归一到满标度） ====================

    /**
     * 稳定度的**满标度**。
     *
     * 数据源 {@code getDFCStabilization()} 不是 0~1 的比值，而是**各稳定器逐 tick
     * 累加的无量纲值**：诅咒版 MixinTileEntityCoreStabilizer 执行
     * {@code core.setDFCStabilization(core.getDFCStabilization() + lens.fieldMod * watts / 100)}，
     * 其中 watts∈[1,100]，透镜 fieldMod 为 blank 0.5 / booster 0.8 / limiter 1.5 /
     * omega 5.0 / safe 8.0。
     *
     * 满标度取 10 的依据是作者自己的 GUI：稳定度进度条宽度 =
     * {@code clampedLerp(0, 70, sqrt(stab / 10))} —— stab = 10 时正好 70px 满格
     * （两台 omega 透镜的稳定器 = 5.0 + 5.0 = 10，条刚好画满）。
     *
     * 早期版本直接把它当 0~1 比值送进 {@code percent()} 再夹到 999，
     * 于是永远显示 999% —— 这就是"监测有问题"里最扎眼的那一处。
     */
    public static final double STAB_FULL_SCALE = 10.0D;

    /** 稳定度 -> 0~1 的饱满度（以 {@link #STAB_FULL_SCALE} 为满，超出夹住） */
    public static double stabilizationRatio(double stabilization) {
        double r = stabilization / STAB_FULL_SCALE;
        if (r < 0.0D) {
            return 0.0D;
        }
        return r > 1.0D ? 1.0D : r;
    }

    /** 稳定度百分比（以 {@link #STAB_FULL_SCALE} 为 100%） */
    public static String stabilization(double stabilization) {
        return percent(stabilizationRatio(stabilization));
    }

    /**
     * 熔毁（collapse）等级：0 正常 / 1 降级（≥50%）/ 2 危急（≥85%）。
     *
     * collapsing 是 0~1 的熔毁进度（作者代码 {@code clamp(1 - explosionIn/120, 0, 1)}，
     * explosionIn 为 120s 倒数），也就是"核心崩溃的程度"——
     * 屏幕上那个大圆盘显示的就是它。
     */
    public static int collapseLevel(double collapsing01) {
        if (collapsing01 >= 0.85D) {
            return 2;
        }
        if (collapsing01 >= 0.50D) {
            return 1;
        }
        return 0;
    }

    /**
     * 三级状态的中文文案（0 正常 / 1 警告 / 2 危急）。
     *
     * 面板上所有"状态"都是同一套三级语义，统一走这里，
     * 避免温度写 STABLE、崩溃写 NOMINAL、稳定度又写一声不吭——三套词玩家得学三遍。
     */
    public static String levelLabel(int level) {
        switch (level) {
            case 2:
                return "危急";
            case 1:
                return "警告";
            default:
                return "正常";
        }
    }

    /** 熔毁状态文案（同 {@link #levelLabel}，保留独立名以表明语义） */
    public static String collapseLabel(int level) {
        return levelLabel(level);
    }

    /**
     * 运行时长：[H:MM:SS]，贴合参考图的 "[0:04:29]"。
     * tick 数按 20/s 换算；超过 24 小时就自然进位到更大的小时数（不截断）。
     */
    public static String duration(int ticks) {
        long total = Math.max(0, ticks) / 20L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        return String.format(java.util.Locale.ROOT, "[%d:%02d:%02d]", h, m, s);
    }

    /** 短倒计时：mm:ss，用于危险提示 */
    public static String countdown(double ticks) {
        if (ticks < 0.0D) {
            return "--:--";
        }
        long total = (long) (ticks / 20.0D);
        long m = total / 60L;
        long s = total % 60L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
    }

    /**
     * 热工状态等级：0 = 正常，1 = 警告（超熔点 85%），2 = 危急（超熔点）。
     * 界面据此换色，所以单独抽出来做纯函数。
     */
    public static int heatLevel(double temperature, int meltingPoint) {
        if (meltingPoint <= 0) {
            return 0;
        }
        double ratio = temperature / (double) meltingPoint;
        if (ratio >= 1.0D) {
            return 2;
        }
        if (ratio >= 0.85D) {
            return 1;
        }
        return 0;
    }

    /** 热工状态文案 */
    public static String heatLabel(int level) {
        return levelLabel(level);
    }

    /** 稳定度状态文案 */
    public static String stabLabel(int level) {
        return levelLabel(level);
    }

    /**
     * 稳定度等级：0 正常 / 1 警告（&lt;99%）/ 2 危急（&lt;90%）。
     * 入参必须是**归一化后的 0~1 饱满度**（即 {@link #stabilizationRatio} 的返回值），
     * 不是 {@code getDFCStabilization()} 的原始累计值。
     */
    public static int stabLevel(double stabilization01) {
        if (stabilization01 < 0.90D) {
            return 2;
        }
        if (stabilization01 < 0.99D) {
            return 1;
        }
        return 0;
    }

    // ==================== 涡轮 / 飞轮 ====================

    /**
     * 转速的**满标度**（转/秒，rps）。
     *
     * 模块化涡轮的转速没有硬上限，但作者自己的调速器把稳态转速锁在
     * {@code GOVERNED_RPS} 附近（几十 rps），再往上只是过速损伤。
     * 取 100 rps 作"条画满"的参考值：既能让常规运转（10~60 rps）
     * 在进度条上有肉眼可辨的区分度，又不至于常态就顶格。
     */
    public static final double SPIN_FULL_SCALE = 100.0D;

    /** 转速：转/秒，保留 2 位小数（与全局规则一致） */
    public static String spin(double rps) {
        return String.format(java.util.Locale.ROOT, "%.2f rps", rps);
    }

    /** 转速 -> 0~1 饱满度（以 {@link #SPIN_FULL_SCALE} 为满，超出夹住） */
    public static double spinRatio(double rps) {
        double r = rps / SPIN_FULL_SCALE;
        if (r < 0.0D) {
            return 0.0D;
        }
        return r > 1.0D ? 1.0D : r;
    }

    // ==================== DFC 核心的能量（SPK，不是 HE） ====================

    /**
     * DFC 能量**原始值 -> SPK** 的换算系数。
     *
     * 【为什么是 ×1000，而不是原样当 SPK】作者自己的 CoreGUI 是
     * {@code SIPfx.formatNoSpace("%01.3f", getDFCContainedEnergy() * 1000.0, false) + "SPK"}，
     * 也就是"原始值乘 1000 再按 SI 加前缀，单位写 SPK"。
     * 截图里那块 tooltip 的 {@code Contained Energy: 0.000SPK} 就是这条路径出来的
     * （0 乘多少都是 0，所以小数位对得上）。
     * Expelling Energy 走的是另一条等价路径：{@code String.format("%01.3f", v / 1000.0) + "MSPK/s"}
     * —— 除以 1000 写成 MSPK，与乘 1000 写成 SPK 是同一个数。
     *
     * 【早期版本错在哪】直接把这几个量当成 HBM 的 HE 显示（{@code DfcFormat.energy()}），
     * 于是面板上写 "4.82 GHE"，而游戏本体在同一台核心上写 "4.82 GSPK" 之外还差 1000 倍的量级。
     * HE 是 HBM 通用机器的能量单位，DFC 这条链路上从头到尾只有 SPK。
     */
    public static final double SPK_SCALE = 1000.0D;

    /** DFC 能量原始值 -> "xx.xx kSPK / MSPK / GSPK…" */
    public static String spk(double raw) {
        return si(raw * SPK_SCALE, "SPK");
    }

    /** DFC 能量原始值 -> 每秒速率（"能量爆发"用） */
    public static String spkRate(double raw) {
        return si(raw * SPK_SCALE, "SPK") + "/s";
    }

    /**
     * 核心积蓄能量进度条的**满刻度**（原始值）。
     *
     * 取 100000 是照抄作者 CoreGUI 的那根条：{@code clampedLerp(0, 70, containedEnergy / 100000.0)}。
     * 【为什么不用 getDFCMaxEnergy】它返回写死的 10000，而且在整个 mod 里**从来没有被调用过**
     * （只在 IMixinTileEntityCore / MixinTileEntityCore 里定义），是 HBM 原版留下来的死常量。
     * 用它当满刻度会让进度条在正常工况下就顶格。
     */
    public static final double CORE_ENERGY_BAR_SCALE = 100000.0D;

    /**
     * 爆堆阈值（failsafeLevel，原始值）。
     * 作者拿它做两件事：tooltip 超过它就整条显示 "ERROR"、以及把 containedEnergy 夹在它以下。
     * 也就是"核心能量到这里就出事了"的那条线。
     */
    public static final double CORE_FAILSAFE = 2.5E8D;

    /**
     * 潜在增益（potentialGain）：**1 起步的倍率**，不是能量。
     *
     * 作者的 GUI 把它当指针读数：{@code angle = min((gain - 1) / 9, client_maxDial) * 360}，
     * tooltip 里则是 {@code Math.round(gain * 100) + "%"}。
     * 早期版本按 "HE/t" 显示（"210.00 kHE/t"）—— 一个 1~10 的倍率被写成几十万的能量。
     */
    public static String gainFactor(double potentialGain) {
        return String.format(java.util.Locale.ROOT, "%.2f", potentialGain);
    }

    /** 潜在增益 -> 指针扫过的 0~1 比例（gain=1 指向起点，gain=10 扫满一圈） */
    public static double gainRatio(double potentialGain) {
        double r = (potentialGain - 1.0D) / 9.0D;
        if (r < 0.0D) {
            return 0.0D;
        }
        return r > 1.0D ? 1.0D : r;
    }
}
