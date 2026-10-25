package com.jiezhimod.dfc;

/**
 * 标注显示器（第五页）的**布局与指标目录** —— 纯函数、无 AWT、无 Minecraft 依赖。
 *
 * 【这块屏要做什么】
 * 参考图里那台机器的屏幕：正中间一个设备示意图，四周用引线拉出若干
 * 「小标题 + 大数值」的标注块（电力生产 / 压力 / 温度等级 / 完整度 …）。
 * 好处是不用懂面板分区，扫一眼就知道每个数字是干什么的。
 *
 * 【为什么"每个格子显示什么"要可配置】
 * 同一个 DFC 堆，有人只关心温度和坍缩倒计时，有人盯着发射器台数和燃料。
 * 固定死的七个指标总有一半是废行，所以这里把七个位置做成**指标槽**，
 * 用调试棒蹲下右键打开界面逐格切换（见 {@link #nextMetric}）。
 *
 * 【为什么抽成纯函数】
 * "第几格画在第几行、引线扎到示意图的哪一点、这个指标该怎么格式化、
 * 该算正常还是危急"全是可断言的映射关系，离线自测里就能钉住
 * （比如"七格在左右两栏里不许重叠"、"切到底要能绕回第一个"）。
 */
public final class LabelLayout {

    // ==================== 指标目录 ====================
    // 编号会写进网络包与存档，**只能在末尾追加，不能改已有值**。

    /** 空槽：画一根短横线占位，不接任何数据 */
    public static final int M_NONE = 0;
    /** 温度（摄氏度，熔点百分比决定颜色） */
    public static final int M_TEMP = 1;
    /** 稳定度（累加值归一化后的百分比） */
    public static final int M_STAB = 2;
    /** 蕴含能量（SPK） */
    public static final int M_ENERGY = 3;
    /** 发电量（接收器合计 J/t，拿不到接收器时退化成核心喷出量 SPK/s） */
    public static final int M_POWER = 4;
    /** 坍缩度（0~100%，涨满即黑洞） */
    public static final int M_COLLAPSE = 5;
    /** 双燃料罐液位 */
    public static final int M_FUEL = 6;
    /** 熔毁倒计时（秒→mm:ss） */
    public static final int M_COUNTDOWN = 7;
    /** 运行时长 [H:MM:SS] */
    public static final int M_RUNTIME = 8;
    /** 结构完整度（12 格里有几格在） */
    public static final int M_STRUCT = 9;
    /** 发射器台数 */
    public static final int M_EMITTER = 10;
    /** 接收器台数 */
    public static final int M_RECEIVER = 11;
    /** 燃料喷射器台数 */
    public static final int M_INJECTOR = 12;
    /** 稳定器台数 */
    public static final int M_STABILIZERS = 13;
    /** 潜在增益倍率 */
    public static final int M_GAIN = 14;

    /** 指标总数（{@link #nextMetric} 的模就是它） */
    public static final int METRIC_COUNT = 15;

    // ==================== 状态等级 ====================
    // 只给语义，配色交给画布（LabelLayout 不认识颜色）。

    public static final int L_OK = 0;      // 绿
    public static final int L_WARN = 1;    // 黄
    public static final int L_CRIT = 2;    // 红
    public static final int L_INFO = 3;    // 青（中性读数）
    public static final int L_MUTE = 4;    // 灰（空槽 / 未连接）

    // ==================== 几何（逻辑 256x192） ====================

    /** 标注格数量：左三右四，正好围着中央示意图一圈 */
    public static final int SLOTS = 7;

    /**
     * 每格"小标题"那一行的基线 y。
     *
     * 【为什么是手排的七个常数，而不是均分】左三右四，两边行高本来就不一样；
     * 均分会把右侧第一格顶到顶栏里去。这组值在下方示意图的锚点之间挪过几轮，
     * 自测里钉了"同一栏内相邻两格至少隔 26 像素"，防止后来人改动时叠在一起。
     */
    public static final int[] SLOT_Y = {42, 98, 154, 31, 65, 125, 163};

    /** true = 这块标注在右栏（数值左对齐），false = 左栏（数值右对齐） */
    public static final boolean[] SLOT_RIGHT = {false, false, false, true, true, true, true};

    /**
     * 引线在示意图上的落点。
     *
     * 【最后两条为什么都落在底座】坍缩程度（左下）与熔毁倒计时（右下）在版面上
     * 挨得最近，若一条扎腰部、一条扎底座，两条斜线会在底部交叉成一个 X ——
     * 离屏图上一眼就能看出"这两条到底谁指谁"。改成左右各占底座一角后互不相交。
     */
    public static final int[] ANCHOR_X = {107, 104, 110, 139, 150, 156, 146};
    public static final int[] ANCHOR_Y = {56, 118, 150, 45, 72, 108, 152};

    /** 左栏文字右边界（引线从这往右伸） */
    public static final int LEFT_TEXT_RIGHT = 92;
    /** 右栏文字左边界（引线从这往左伸） */
    public static final int RIGHT_TEXT_LEFT = 164;
    /** 标注块可用的文字宽度（左右两栏同宽） */
    public static final int TEXT_W = 86;

    /** 小标题字号 / 数值最大字号 / 数值最小字号 */
    public static final int LABEL_SIZE = 8;
    public static final int VALUE_MAX_SIZE = 15;
    public static final int VALUE_MIN_SIZE = 9;

    /** 引线折角：先横一小段，再斜着扎到示意图上，看起来才像"标注"而不是"射线" */
    public static final int ELBOW_LEFT = 98;
    public static final int ELBOW_RIGHT = 158;

    /** 中央示意图的包围盒（自测用，防止锚点跑到图外面） */
    public static final int FIG_L = 100;
    public static final int FIG_R = 156;
    public static final int FIG_T = 38;
    public static final int FIG_B = 160;

    private LabelLayout() {
    }

    // ==================== 槽位操作 ====================

    /** 下一格内容（循环：最后一项之后回到"空"） */
    public static int nextMetric(int cur) {
        int m = cur + 1;
        if (m >= METRIC_COUNT) {
            m = 0;
        }
        return clampMetric(m);
    }

    /** 上一格内容 */
    public static int prevMetric(int cur) {
        int m = cur - 1;
        if (m < 0) {
            m = METRIC_COUNT - 1;
        }
        return clampMetric(m);
    }

    /**
     * 夹进合法范围。非法值一律当"空" ——
     * 存档来自旧版本（或手改过）时宁可显示一根横线，也不要画出乱七八糟的东西。
     */
    public static int clampMetric(int m) {
        return m < 0 || m >= METRIC_COUNT ? M_NONE : m;
    }

    /**
     * 补齐/校验一整组槽位（读 NBT、收网络包时用）。
     *
     * @return 长度恒为 {@link #SLOTS} 的新数组，越界值清成 {@link #M_NONE}
     */
    public static int[] sanitize(int[] src) {
        int[] out = new int[SLOTS];
        if (src == null) {
            return out;
        }
        for (int i = 0; i < SLOTS && i < src.length; i++) {
            out[i] = clampMetric(src[i]);
        }
        return out;
    }

    /** 出厂配置：左边温度 / 稳定度 / 坍缩度，右边能量 / 发电量 / 结构 / 倒计时 */
    public static int[] defaults() {
        return new int[]{M_TEMP, M_STAB, M_COLLAPSE, M_ENERGY, M_POWER, M_STRUCT, M_COUNTDOWN};
    }

    // ==================== 文案 ====================

    /**
     * 指标的中文名（小标题那一行）。
     *
     * 【为什么写在这里而不是语言文件】这一行是 AWT 直接画到位图上的
     * （见 ScreenCanvas），走的不是 MC 的字体与 I18n 链路；
     * 而配置界面也要显示同一个名字，两处共用一个方法才不会出现
     * "界面里叫'发电量'、屏上叫'输出功率'"这种漂移。
     */
    public static String metricName(int metric) {
        switch (metric) {
            case M_TEMP: return "温度等级";
            case M_STAB: return "稳定度";
            case M_ENERGY: return "蕴含能量";
            case M_POWER: return "电力生产";
            case M_COLLAPSE: return "坍缩程度";
            case M_FUEL: return "燃料储量";
            case M_COUNTDOWN: return "熔毁倒计时";
            case M_RUNTIME: return "运行时长";
            case M_STRUCT: return "结构完整度";
            case M_EMITTER: return "发射器";
            case M_RECEIVER: return "接收器";
            case M_INJECTOR: return "燃料喷射器";
            case M_STABILIZERS: return "稳定器";
            case M_GAIN: return "潜在增益";
            default: return "空";
        }
    }

    // ==================== 读数 ====================

    /**
     * 这一格当前该显示的数值文本。
     *
     * @param d     数据快照，可以为 null（离线自测会直接传 null）
     * @param metric 指标编号
     */
    public static String valueText(DfcSnapshot d, int metric) {
        if (metric == M_NONE) {
            return "--";
        }
        if (d == null || !d.linked) {
            // 没绑数据源时连 0 都不能显示：屏幕上写"0"会被当成真实读数
            return "--";
        }
        switch (metric) {
            case M_TEMP:
                return DfcFormat.temperature(d.temperature);
            case M_STAB:
                return DfcFormat.stabilization(d.stabilization);
            case M_ENERGY:
                return DfcFormat.spk(d.containedEnergy);
            case M_POWER:
                // 与曲线页同一个口径：优先所有接收器的合计（J/t），
                // 没有接收器才退化成核心喷出量（SPK/s）
                return d.receiverCount > 0
                        ? DfcFormat.si(d.receiverJoules, "J/t")
                        : DfcFormat.spkRate(d.expellingSpk);
            case M_COLLAPSE:
                return DfcFormat.percent(d.collapsing);
            case M_FUEL:
                return DfcFormat.percent(d.fuelRatioA) + " / " + DfcFormat.percent(d.fuelRatioB);
            case M_COUNTDOWN:
                // secondsToExplosion 是**秒**，DfcFormat.countdown 收的是 tick，
                // 这里乘 20 换算（写反了就会把 10 秒显示成 10 分钟）
                // 没有倒计时（堆没在炸）时写"无"而不是 "--:--"：
                // 等宽字体的冒号在这种字号下就是两个点，"--:--" 看上去像四个灰尘
                return d.secondsToExplosion >= 0.0F
                        ? DfcFormat.countdown(d.secondsToExplosion * 20.0D)
                        : "无";
            case M_RUNTIME:
                return DfcFormat.duration(d.ticks);
            case M_STRUCT:
                return DfcFormat.percentRaw(d.componentCount * 100.0D / ScreenStructure.PARTS);
            case M_EMITTER:
                return d.emitterCount + " 台";
            case M_RECEIVER:
                return d.receiverCount + " 台";
            case M_INJECTOR:
                return d.injectorCount + " 台";
            case M_STABILIZERS:
                return d.stabilizers + " 台";
            case M_GAIN:
                return DfcFormat.gainFactor(d.potentialGain);
            default:
                return "--";
        }
    }

    /**
     * 这一格的状态等级（决定数值颜色）。
     *
     * 判据全部复用 {@link DfcFormat} 里那几把"同一把尺子" —— 屏幕上同一个量
     * 在核心页是黄色、在标注页是绿色，会让人怀疑哪个才是对的。
     */
    public static int level(DfcSnapshot d, int metric) {
        if (metric == M_NONE) {
            return L_MUTE;
        }
        if (d == null || !d.linked) {
            return L_MUTE;
        }
        // 冷堆（已绑定但温度没到 100°C）：除了温度本身，其余一律灰 ——
        // 那些量此刻都是 0，标成绿色"正常"会让人以为堆在正常运行。
        // 与 {@link #coldOffline} 是同一个判据，画字和画色不会打架。
        if (ScreenLayout.offline(d) && metric != M_TEMP) {
            return L_MUTE;
        }
        switch (metric) {
            case M_TEMP: {
                if (!ScreenLayout.online(d)) {
                    return L_MUTE;   // 冷堆：温度还没到工作值，一切读数没意义
                }
                int lv = DfcFormat.heatLevel(d.temperature, d.meltingPoint);
                return lv == 2 ? L_CRIT : lv == 1 ? L_WARN : L_OK;
            }
            case M_STAB: {
                if (!ScreenLayout.online(d)) {
                    return L_MUTE;
                }
                int lv = DfcFormat.stabLevel(DfcFormat.stabilizationRatio(d.stabilization));
                return lv == 2 ? L_CRIT : lv == 1 ? L_WARN : L_OK;
            }
            case M_COLLAPSE: {
                int lv = DfcFormat.collapseLevel(d.collapsing);
                return lv == 2 ? L_CRIT : lv == 1 ? L_WARN : L_INFO;
            }
            case M_FUEL:
                return Math.min(d.fuelRatioA, d.fuelRatioB) < 0.15D ? L_WARN : L_INFO;
            case M_COUNTDOWN:
                if (d.secondsToExplosion < 0.0F) {
                    return L_MUTE;
                }
                return d.secondsToExplosion <= 30.0F ? L_CRIT : L_WARN;
            case M_STRUCT:
                return d.componentCount >= ScreenStructure.PARTS ? L_OK : L_WARN;
            case M_EMITTER:
                return d.emitterCount > 0 ? L_INFO : L_MUTE;
            case M_RECEIVER:
                return d.receiverCount > 0 ? L_INFO : L_MUTE;
            case M_INJECTOR:
                return d.injectorCount > 0 ? L_INFO : L_MUTE;
            case M_STABILIZERS:
                return d.stabilizers > 0 ? L_INFO : L_MUTE;
            default:
                return L_INFO;
        }
    }

    /**
     * 这一格是否用"离线"两个字代替数字。
     *
     * 与 {@link ScreenLayout#online} 同一个判据：绑了数据源但温度没到 100°C。
     * 未绑定（{@code !linked}）时不走这里 —— 那种情况整屏就是离线页。
     */
    public static boolean coldOffline(DfcSnapshot d, int metric) {
        if (d == null || !d.linked || metric == M_NONE) {
            return false;
        }
        if (metric == M_TEMP) {
            return false;   // 温度本身就是要看的数，即使很低也照实显示
        }
        return ScreenLayout.offline(d);
    }
}
