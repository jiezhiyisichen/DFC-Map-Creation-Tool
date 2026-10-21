package com.jiezhimod.dfc;

import java.util.ArrayList;
import java.util.List;

/**
 * 显示器画面的**状态机与布局决策**（纯函数，无 AWT / 无 Minecraft 依赖）。
 *
 * 抽出来的理由和 DfcFormat 一样：这些是"时间 -> 该显示什么"、"数据变化 -> 该记什么日志"
 * 的映射关系，最容易写错边界（开机几秒？日志几行？阈值多少算危急？），
 * 而它们在离线自测里完全可以断言，没必要等到进游戏看。
 *
 * 画面尺寸固定 256x192（4:3），对应 4x3 格的屏幕。
 */
public final class ScreenLayout {

    // ==================== 时间轴 ====================
    /** 开机画面（节枝OS）时长 */
    public static final long BOOT_MS = 1100L;
    /** 控制台每行日志出现的间隔 */
    public static final long CONSOLE_LINE_MS = 240L;
    /** 控制台阶段额外停留时间（日志出完后再等一会） */
    public static final long CONSOLE_TAIL_MS = 600L;

    public static final int PHASE_BOOT = 0;
    public static final int PHASE_CONSOLE = 1;
    public static final int PHASE_MAIN = 2;

    // ==================== 熔毁报错页 ====================
    //
    // 需求："在进入熔毁阶段时，显示全屏报错301，与核心失去连接，等待4秒后，重启屏幕"。
    //
    // 【触发判据】用户确认：熔毁 = **坍缩度从 0 变成 >0 的那一瞬间**。
    // 作者代码里 {@code collapsing = clamp(1 - explosionIn/120, 0, 1)}，
    // 所以 collapsing > 0 就等于"爆炸倒计时已经启动"——就是核心开始炸的那一刻。

    /** 报错页停留时长（毫秒）。到点就把屏幕重启（重走一遍开机自检） */
    public static final long FAULT_MS = 4000L;
    /** 故障码：与核心失去连接 */
    public static final int FAULT_CODE = 301;
    /** 故障正文（与故障码配对，需求原话） */
    public static final String FAULT_TEXT = "与核心失去连接";

    /**
     * 数据刷新周期 —— **屏幕读数和画布重绘必须共用这一个节拍**。
     *
     * 服务端每 {@link #REFRESH_TICKS} tick 读一次 DFC 数据并广播；
     * 客户端画布每 {@link #REFRESH_MS} 毫秒重绘一次。两者定成同一个数，
     * 数据一到就正好落在下一个重绘周期上，不会出现"读数和画面差一拍"。
     *
     * 放在这里（纯函数区）而不是各自的类里，是为了让离线自测能断言它们对齐 ——
     * 一个在 block 包、一个在 client 包，谁都引不动对方。
     *
     * 【0.05 秒 = 1 tick】需求要求"所有显示屏刷新率 0.05 秒"。
     * 50ms 恰好是 MC 的**一个 tick**，所以服务端可以做到"每 tick 读一次并广播"，
     * 画布也每 tick 重绘一帧 —— 读数与画面之间没有任何插值/滞后。
     * 代价是网络包量是原来（200ms）的 4 倍，对"盯着实时读数"的监视屏是值的。
     */
    public static final long REFRESH_MS = 50L;
    /** 同上，折算成 tick（20 tick/s）：50ms = 1 tick */
    public static final int REFRESH_TICKS = 1;

    /**
     * "反应堆在线"的最低温度（摄氏度）。
     *
     * 需求：**未到 100°C 视为离线**。冷堆（刚放下、或已经停堆降温）温度是 0，
     * 此时一切读数都还没意义 —— 稳定度 0、能量 0、坍缩 0。
     * 若不加这条，冷堆会被判成"稳定度危急"（稳定度 0 < 90% 阈值），
     * 屏幕上永远挂着一个红色的假警报。离线态的判定集中在这里，
     * 让画布、事件日志、告警三处用的是同一个口子。
     */
    public static final double ONLINE_MIN_TEMP = 100.0D;

    /** 数据源已绑定且反应堆已经"热起来"（温度 >= {@link #ONLINE_MIN_TEMP}） */
    public static boolean online(DfcSnapshot d) {
        return d != null && d.linked && d.temperature >= ONLINE_MIN_TEMP;
    }

    /** 绑上了数据源、但反应堆还没到工作温度 —— 界面显示"离线"而不是一堆 0 */
    public static boolean offline(DfcSnapshot d) {
        return d != null && d.linked && d.temperature < ONLINE_MIN_TEMP;
    }

    /** 屏幕分辨率 */
    public static final int W = 256;
    public static final int H = 192;

    // ==================== 开机控制台日志 ====================
    /**
     * 逐行吐出的开机日志。内容按"一个真实设备的启动流程"写，
     * 但设备名留着 DFC 的特征，让玩家一眼看懂这屏连的是什么。
     */
    public static final String[] BOOT_LOG = {
            "JIEZHI-OS 1.4.2  (build 20171003)",
            "POST ................................ OK",
            "display controller ....... 4x3x1 LCD",
            "probing DFCLINK bus ................ OK",
            "  dev0  DARK FUSION CORE      [bound]",
            "  dev1  DFC STABILIZER        [bound]",
            "mounting telemetry endpoints",
            "  core.temperature ........... online",
            "  core.stabilization ......... online",
            "  core.energy ................ online",
            "  core.struct ................ online",
            "loading gauge module ............... OK",
            "starting renderer .................. OK",
            "ready.",
    };

    private ScreenLayout() {
    }

    /** 当前处于哪个阶段（相对"按下开关"过去了多少毫秒） */
    public static int phaseAt(long sinceOnMs) {
        if (sinceOnMs < BOOT_MS) {
            return PHASE_BOOT;
        }
        if (sinceOnMs < BOOT_MS + CONSOLE_MS()) {
            return PHASE_CONSOLE;
        }
        return PHASE_MAIN;
    }

    /** 控制台阶段总时长 = 日志吐完 + 尾部停顿 */
    public static long CONSOLE_MS() {
        return BOOT_LOG.length * CONSOLE_LINE_MS + CONSOLE_TAIL_MS;
    }

    /** 开机画面到主界面的总耗时 */
    public static long totalBootMs() {
        return BOOT_MS + CONSOLE_MS();
    }

    /** 控制台阶段已经吐出多少行（0 .. BOOT_LOG.length） */
    public static int consoleLineCount(long sinceOnMs) {
        if (sinceOnMs <= BOOT_MS) {
            return 0;
        }
        long n = (sinceOnMs - BOOT_MS) / CONSOLE_LINE_MS + 1L;
        if (n < 0L) {
            return 0;
        }
        return (int) Math.min(n, BOOT_LOG.length);
    }

    /** 开机 logo 的闪烁相位（每 260ms 翻转一次，做出老显示器"光标闪"的味道） */
    public static boolean bootCursorVisible(long sinceOnMs) {
        return (sinceOnMs / 260L) % 2L == 0L;
    }

    /** 控制台当前正在"输入"的那一行末尾也带光标 */
    public static int consoleCursorLine(long sinceOnMs) {
        return consoleLineCount(sinceOnMs) - 1;
    }

    // ==================== 运行期事件日志 ====================

    /** 单条事件日志最多保留多少行（右栏"记录条目"） */
    public static final int LOG_CAPACITY = 12;

    /**
     * 比较前后两帧数据，产出需要追加到事件日志的行。
     *
     * 只在**跨越阈值**时记一条，而不是每帧都记 —— 否则日志会被同一个告警刷屏。
     * 返回的列表顺序就是"先发生的在前"。
     *
     * 【文案全中文】右栏只有 92 逻辑像素宽，中文字号 9 时一行 9 个字。
     * 所以每句都得压到 9 字上下，"温度危急 3587°C" 这种长度刚好；
     * 写成 "core temperature reached critical level" 会直接被截断成省略号。
     * 前缀 "!!" / "##" 保留 —— 它们是等级信号灯，且只占半格。
     */
    public static List<String> pendingEvents(DfcSnapshot prev, DfcSnapshot cur) {
        List<String> out = new ArrayList<String>();
        if (cur == null) {
            return out;
        }
        if (prev == null || !prev.linked) {
            out.add("已连接 " + sourceName(cur));
            if (cur.hasCore) {
                out.add("堆芯上线 结构" + cur.componentCount + "件");
            }
            return out;
        }

        // 热工
        int lvPrev = DfcFormat.heatLevel(prev.temperature, prev.meltingPoint);
        int lvCur = DfcFormat.heatLevel(cur.temperature, cur.meltingPoint);
        if (lvCur > lvPrev) {
            out.add(lvCur == 2
                    ? "!! 温度危急 " + DfcFormat.temperature(cur.temperature)
                    : "! 温度升高 " + DfcFormat.temperature(cur.temperature));
        } else if (lvCur < lvPrev) {
            out.add("温度回落 " + DfcFormat.levelLabel(lvCur));
        }

        // 稳定度（带运行上下文：停堆时 0 稳定度不算"下降"，见 stabLevel）
        int stPrev = stabLevel(prev);
        int stCur = stabLevel(cur);
        if (stCur > stPrev) {
            out.add((stCur == 2 ? "!! " : "! ") + "稳定度下降 "
                    + DfcFormat.stabilization(cur.stabilization));
        } else if (stCur < stPrev) {
            out.add("稳定度恢复 " + DfcFormat.stabilization(cur.stabilization));
        }

        // 在线/离线（温度跨过 ONLINE_MIN_TEMP）—— 低于 100°C 期间所有读数都不可信，
        // 这条日志是操作员判断"画面上的 0 到底是真读数还是没开机"的唯一线索。
        boolean onPrev = online(prev);
        boolean onCur = online(cur);
        if (onCur && !onPrev) {
            out.add("堆芯在线 已达工作温度");
        } else if (!onCur && onPrev) {
            out.add("! 堆芯离线 温度不足");
        }

        // 结构
        if (cur.stabilizers != prev.stabilizers) {
            out.add("稳定器 " + prev.stabilizers + " -> " + cur.stabilizers + " 台");
        }
        if (cur.componentCount != prev.componentCount) {
            out.add("结构变化 " + cur.componentCount + " 件");
        }
        if (cur.hasCore != prev.hasCore) {
            out.add(cur.hasCore ? "燃料核心 已装入" : "燃料核心 已移除");
        }
        if (cur.boosted != prev.boosted) {
            out.add(cur.boosted ? "增强透镜 已启用" : "增强透镜 已停用");
        }

        // 危险
        if (cur.collapsing > prev.collapsing && DfcFormat.collapseLevel(cur.collapsing) == 2
                && DfcFormat.collapseLevel(prev.collapsing) != 2) {
            out.add("!! 崩溃场 " + DfcFormat.percent(cur.collapsing));
        }
        if (cur.overloadTimer > 0 && prev.overloadTimer == 0) {
            out.add("!! 检测到过载 倒计时中");
        }
        if (cur.detonation && !prev.detonation) {
            out.add("## 引爆序列已启动");
        }
        if (cur.destroyed && !prev.destroyed) {
            out.add("## 堆芯已摧毁");
        }
        if (cur.explosionIn >= 0.0D && prev.explosionIn < 0.0D) {
            out.add("## 熔毁倒计时 " + DfcFormat.countdown(cur.explosionIn));
        }
        return out;
    }

    /**
     * 数据源在人话里的名字 —— **一律用游戏语言文件里的官方中文名**，
     * 不要自己另起一个（"暗聚变堆芯"是我早先自造的，官方叫「暗核聚变堆核心」，
     * 语言文件里 `tile.dfc_core.name` / `container.dfcCore` 都是这一条）。
     *
     * 稳定器同理：`tile.dfc_stabilizer.name` = 「DFC稳定器」（中间**没有空格**）。
     * 这两条名字会同时出现在三块屏的顶栏和核心页的事件日志里，
     * 一旦和创造模式物品栏里的名字对不上，玩家会以为屏幕认错了机器。
     */
    public static String sourceName(DfcSnapshot s) {
        if (s == null || !s.linked) {
            return "未连接";
        }
        switch (s.sourceKind) {
            case DfcSnapshot.SOURCE_CORE:
                return "暗核聚变堆核心";
            case DfcSnapshot.SOURCE_STABILIZER:
                return "DFC稳定器";
            default:
                return "未知数据源";
        }
    }

    /**
     * 温度趋势（每秒变化量）。
     * 界面上的 "+2/s" 用的是这个：由快照里本 tick 的 gained/expelling 推不出来，
     * 所以用"上一帧温度 - 这一帧温度"再按刷新间隔折算。
     */
    public static double temperatureRate(double prevTemp, double curTemp, long deltaTicks) {
        if (deltaTicks <= 0L) {
            return 0.0D;
        }
        return (curTemp - prevTemp) / (double) deltaTicks;
    }

    // ==================== 显示哪一页 ====================
    //
    // 四块显示器（核心屏 / 部件监视器 / 自定义屏 / 曲线屏）是同一台硬件、
    // 同一套多方块外壳，唯一差别就是主界面画哪一页。判定放在这里是为了能在离线
    // 自测里断言，而不是埋进需要 OpenGL 的渲染类里。

    /** 页面 0：核心遥测（温度 / 稳定度 / 能量 / 崩溃圆盘 / 事件日志） */
    public static final int PAGE_CORE = 0;
    /** 页面 1：部件遥测（发射器 / 接收器 / 燃料 / 燃料喷射器 / 积蓄能量 / 能量爆发） */
    public static final int PAGE_COMPONENTS = 1;
    /** 页面 2：自定义数据列表（最多 8 项，由 GUI 增删） */
    public static final int PAGE_CUSTOM = 2;
    /** 页面 3：折线图（温度 + 发电量两条历史曲线，自动量程） */
    public static final int PAGE_GRAPH = 3;

    /**
     * 由"控制器方块是哪一种显示器"决定画哪一页。
     *
     * 【注意】判定必须基于**控制器**方块，不能看占位块 ——
     * 11 个占位块四块显示器共用，从它身上分辨不出是哪一种屏。
     */
    public static int pageFor(boolean customScreen, boolean componentMonitor, boolean graphScreen) {
        if (customScreen) {
            return PAGE_CUSTOM;
        }
        if (graphScreen) {
            return PAGE_GRAPH;
        }
        return componentMonitor ? PAGE_COMPONENTS : PAGE_CORE;
    }

    /** 兼容旧调用：没有曲线屏时等价于三参版本传 false */
    public static int pageFor(boolean customScreen, boolean componentMonitor) {
        return pageFor(customScreen, componentMonitor, false);
    }

    /**
     * 是否进入"核心崩溃告警"（整屏转红 + 顶部闪烁 !! + 盘心变黄）。
     *
     * 【为什么要带上 linked + online】未绑定数据源时快照是空的、冷堆（温度 < 100）
     * 时坍缩度也必然是 0，本来就不会误报；但反过来，一旦哪天有人把 collapsing
     * 的默认值改错，这两条就是最后一道闸 —— 一块没接数据源、或者反应堆还没热起来的屏，
     * 绝不该在屏幕上闪"!!"。离线判定与画面用的是同一个 {@link #online}，不会出现
     * "画面说离线、顶上却在报警"这种自相矛盾。
     */
    public static boolean alertActive(DfcSnapshot d) {
        return online(d) && d.collapsing > 0.0D;
    }

    /**
     * 崩溃告警的严重档位，用来决定盘心颜色：
     * 0 = 不变黄；1 = 黄（刚开始崩）；2 = 红（过半，快压不住了）。
     *
     * 阈值取 0.5 与 {@link com.jiezhimod.dfc.DfcFormat#collapseLevel(double)} 的
     * "警告/危急"分界对齐 —— 屏幕上一处说危急、另一处还是黄的会让人误判。
     */
    public static int alertLevel(DfcSnapshot d) {
        if (!alertActive(d)) {
            return 0;
        }
        return d.collapsing >= 0.5D ? 2 : 1;
    }

    /**
     * 闪烁相位：把时间按 periodMs/2 切成格子，偶数格亮、奇数格灭。
     *
     * 【为什么要传时间进来，而不是在这里读 System.currentTimeMillis()】
     * 早期版本直接读挂钟，结果离屏导出的两帧永远一模一样 ——
     * "闪烁"这件事既没法肉眼比对也没法断言。改成由调用方传入渲染相位
     * （开机态就是 sinceOnMs），同一时刻的两帧必然一致，不同相位必然不同。
     */
    public static boolean blinkOn(long ms, long periodMs) {
        if (periodMs <= 0L) {
            return true;
        }
        long half = Math.max(1L, periodMs / 2L);
        return ((ms / half) % 2L) == 0L;
    }

    /**
     * 崩溃告警 "!!" 的闪烁周期。
     *
     * 【必须是重绘周期的整数倍】主界面每 {@link #REFRESH_MS} 毫秒重画一帧，
     * 半周期若不能整除重绘周期就会"走样"：某些半周期被采到两次、某些被跳过，
     * 看上去就是忽快忽慢的乱闪。
     *
     * 取 16 倍 = 800ms 整周期（亮 400ms / 灭 400ms，1.25Hz）—— 与刷新率改成
     * 50ms 之前的观感完全一致。**不能因为刷新率提了 4 倍就把倍数也照旧写成 4**：
     * 那样周期会变成 200ms（5Hz），闪得又急又碎，反而看不清。
     */
    public static final long ALERT_BLINK_MS = REFRESH_MS * 16L;

    // ==================== 稳定度（带运行上下文） ====================

    /**
     * 稳定度等级，**带反应堆运行上下文**。
     *
     * 需求："0 度时稳定度为 0 时不显示危急"。
     * {@link DfcFormat#stabLevel(double)} 是纯比值判定：0% 稳定度 → 危急。
     * 可对一台**还没点火的堆**来说，稳定度本来就是 0，那不是"稳定度崩了"，
     * 只是"还没开机"。直接沿用纯比值判定，会让每一块刚放下、还没接上热堆的屏
     * 永远亮着红色的"危急"。
     *
     * 判据取 `温度 <= 0 && 稳定度 <= 0`：两条同时为 0 才是"停堆常态"；
     * 一旦温度起来了，稳定度 0 依然是危急（那是真的没稳定器在压）。
     */
    public static int stabLevel(DfcSnapshot d) {
        double ratio = DfcFormat.stabilizationRatio(d.stabilization);
        if (d.temperature <= 0.0D && ratio <= 0.0D) {
            return 0;
        }
        return DfcFormat.stabLevel(ratio);
    }
}
