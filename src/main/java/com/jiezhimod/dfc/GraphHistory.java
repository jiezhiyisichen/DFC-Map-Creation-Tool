package com.jiezhimod.dfc;

/**
 * 折线图显示器的**历史采样**与**坐标轴量程**（纯逻辑，不 import 任何 Minecraft / AWT 类）。
 *
 * 【为什么要单独一个类】
 * 曲线这块最容易出错的不是"怎么画"，而是三件事：
 *   1. 环形缓冲写满之后最旧的点有没有被真的丢掉（写错就永远是最近 12 秒的锯齿）；
 *   2. 断点（未连接 / 数据源被拆）有没有被当成 0 —— 那会在图上留下一道
 *      "温度突然掉到 0 又跳回来"的假悬崖，操作员会以为堆炸过；
 *   3. 纵轴量程。需求原话是"初始区间为 0-100 度，当温度超过范围时，
 *      以最大的温度设置为区间，也就是现实中的样子"。
 * 这三条都能在离线自测里断言，所以它们全部放在这里，
 * {@code ScreenCanvas} 只负责把 {@link #tempAt(int)} 变成像素。
 *
 * 【采样节奏与窗口长度】
 * 每 {@link #SAMPLE_MS} 毫秒记一个点，环形缓冲 {@link #CAPACITY} 个。
 * 屏幕按自己的刷新率重绘（50 / 100 / 200 / 500 / 1000 ms 可调，见
 * {@code TileDfcScreen#getRefreshMs()}），所以**一次重绘最多采一个点** ——
 * 刷新率调到 1000ms 时窗口会自然拉长到 120 秒。
 * 采样时刻由调用方（画布）传入，本类不读挂钟，保证同一时刻多次调用结果一致。
 */
public final class GraphHistory {

    /** 环形缓冲容量（点数）。120 点 × 500ms ≈ 60 秒的窗口 */
    public static final int CAPACITY = 120;

    /** 采样间隔（毫秒）—— 与重绘周期无关，慢了也不会漏得更密 */
    public static final long SAMPLE_MS = 500L;

    /**
     * 温度纵轴的**初始上界**（摄氏度）。
     *
     * 需求："初始区间为 0-100 度"。所以只要观测到的最高温度没超过 100，
     * 纵轴就一直是 0~100 —— 冷堆、小功率运行都能看出"贴着底"的真实形态，
     * 而不是被自动缩放到 0~3 让一条平线看起来像在山呼海啸。
     */
    public static final double TEMP_BASE_MAX = 100.0D;

    /**
     * 发电量纵轴的初始上界（SPK）。
     *
     * 需求只点了温度，发电量没说 —— 但同一块屏上两张图并排，
     * 一张从 0 起、另一张从 0 到几百万，读起来会很割裂。
     * 所以它也走同一套"先给个底，超了再抬"的规则，
     * 底值取 100 SPK（量级上就是个"几乎没有输出"的起点）。
     */
    public static final double POWER_BASE_MAX = 100.0D;

    /**
     * 纵轴分几格（0 / 25% / 50% / 75% / 100% 共 5 条横线）。
     * 取 4 是因为下面 {@link #niceCeil} 的尾数集合里每个数都能被 4 整除得很干净
     * （1→0.25 / 1.5→0.375 / 2→0.5 / 2.5→0.625 …）—— 见格式化说明。
     */
    public static final int DIVISIONS = 4;

    /** 断点的内部标记（NaN）。用 NaN 而不是 -1：真实读数可能是 0，永远不会是 NaN */
    private final double[] temp = new double[CAPACITY];
    private final double[] power = new double[CAPACITY];

    /** 已经写入的点数（0 .. CAPACITY） */
    private int count;
    /** 下一个写入位置（环形） */
    private int head;

    public GraphHistory() {
        clear();
    }

    /** 清空（关机 / 重开 / 换数据源时调用） */
    public void clear() {
        for (int i = 0; i < CAPACITY; i++) {
            temp[i] = Double.NaN;
            power[i] = Double.NaN;
        }
        count = 0;
        head = 0;
    }

    /**
     * 记一个点。
     *
     * 负数和非有限值一律按"断点"处理：温度不可能是负的，
     * 而 Infinity / NaN 画到图上只会让整条线飞出面板。
     */
    public void push(double tempValue, double powerValue) {
        if (!isFinite(tempValue) || !isFinite(powerValue)) {
            pushGap();
            return;
        }
        temp[head] = Math.max(0.0D, tempValue);
        power[head] = Math.max(0.0D, powerValue);
        advance();
    }

    /** 记一个断点（未连接 / 离线 / 读不到）—— 折线在这里断开，不画成 0 */
    public void pushGap() {
        temp[head] = Double.NaN;
        power[head] = Double.NaN;
        advance();
    }

    private void advance() {
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
    }

    /**
     * 发电量口径：**所有接收器上一 tick 收到能量的总和**。
     *
     * 【为什么不是核心自己的 expelling】需求原话："发电量应该是所有接收器总和"。
     * 一台堆周围的接收器才是真正把 SPK 换成电的地方，
     * 核心那个 {@code getDFCExpellingSpk()} 只是"吐出去多少"，
     * 场上接了 8 台接收器时两者并不相等，而玩家要看的显然是"我这套场子一共发多少"。
     *
     * 口径来源（`DfcBridge` 汇总 {@code TileEntityCoreReceiver#leafia$syncJoules}）：
     * 诅咒版在 tick 末尾做 {@code syncJoules = joules; joules = 0;}，
     * 所以 {@code syncJoules} 就是**这一 tick 收到的能量**，跨接收器求和即是"总发电量"。
     *
     * 【没有接收器时】退回核心自己的吐出量 —— 用涡轮 / 望远镜取能、
     * 或者接收器还没接上时，曲线不至于是一条死的 0 线。
     * 单位随之切换（见 {@link #powerUnit(int)}）。
     */
    public static double powerOf(double receiverJoules, int receiverCount,
                                 double expellingSpk, double expellingEnergy) {
        if (receiverCount > 0) {
            return isFinite(receiverJoules) && receiverJoules > 0.0D ? receiverJoules : 0.0D;
        }
        if (isFinite(expellingSpk) && expellingSpk > 0.0D) {
            return expellingSpk;
        }
        return isFinite(expellingEnergy) && expellingEnergy > 0.0D ? expellingEnergy : 0.0D;
    }

    /** 与 {@link #powerOf} 配对：有接收器时是「每 tick 焦耳」，否则是核心的 SPK */
    public static String powerUnit(int receiverCount) {
        return receiverCount > 0 ? "J/t" : "SPK";
    }

    public int size() {
        return count;
    }

    /** 逻辑下标（0 = 最旧）-> 环形缓冲下标 */
    private int slot(int i) {
        int start = (head - count + CAPACITY * 2) % CAPACITY;
        return (start + i) % CAPACITY;
    }

    public double tempAt(int i) {
        return temp[slot(i)];
    }

    public double powerAt(int i) {
        return power[slot(i)];
    }

    /** 这一个点是不是断点 */
    public boolean gapAt(int i) {
        return Double.isNaN(temp[slot(i)]);
    }

    /** 窗口内观测到的最高温度（忽略断点），没有有效点时为 0 */
    public double tempMax() {
        return maxOf(temp);
    }

    /** 窗口内观测到的最高发电量（忽略断点） */
    public double powerMax() {
        return maxOf(power);
    }

    private double maxOf(double[] src) {
        double m = 0.0D;
        for (int i = 0; i < CAPACITY; i++) {
            double v = src[i];
            if (!Double.isNaN(v) && v > m) {
                m = v;
            }
        }
        return m;
    }

    /** 温度纵轴上界：不小于 100，且把观测到的最高温度包进去 */
    public double tempAxisMax() {
        return niceCeil(Math.max(TEMP_BASE_MAX, tempMax()));
    }

    /** 发电量纵轴上界：同上，底值 100 SPK */
    public double powerAxisMax() {
        return niceCeil(Math.max(POWER_BASE_MAX, powerMax()));
    }

    /** 窗口内有没有任何有效读数（全是断点时返回 false -> 画"等待数据"） */
    public boolean hasData() {
        for (int i = 0; i < CAPACITY; i++) {
            if (!Double.isNaN(temp[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尾数集合。每个都是能被 4 除得比较干净的数，刻度标签不会出现
     * "37.53125" 这种一眼看不懂的值。
     */
    private static final double[] MANTISSA = {1.0D, 1.5D, 2.0D, 2.5D, 3.0D, 4.0D, 5.0D, 6.0D, 8.0D, 10.0D};

    /**
     * 把数值向上取到一个"好看"的刻度上界。
     *
     * 例：100→100，101→150，250→250，812.5→1000，1240→1500，3587→4000。
     * 结果**一定 ≥ 入参**（自测有断言），所以曲线永远不会顶出面板。
     */
    public static double niceCeil(double v) {
        if (!isFinite(v) || v <= 0.0D) {
            return 1.0D;
        }
        double exp = Math.pow(10.0D, Math.floor(Math.log10(v)));
        double m = v / exp;
        for (int i = 0; i < MANTISSA.length; i++) {
            // 1e-9 的相对容差：log10 的浮点误差会让 100/100 = 1.0000000000000002
            if (m <= MANTISSA[i] + 1e-9D) {
                return MANTISSA[i] * exp;
            }
        }
        return 10.0D * exp;
    }

    /** 第 k 条横向网格线对应的值（k = 0 是最上面那条 = 上界） */
    public static double gridValue(double axisMax, int k) {
        return axisMax * (DIVISIONS - k) / (double) DIVISIONS;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
