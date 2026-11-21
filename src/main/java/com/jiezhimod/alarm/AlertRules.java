package com.jiezhimod.alarm;

import com.jiezhimod.dfc.DfcFormat;
import com.jiezhimod.dfc.DfcSnapshot;
import com.jiezhimod.dfc.LaserInfo;
import com.jiezhimod.dfc.ScreenLayout;

/**
 * 「显示屏数据 → 警示灯颜色」的唯一真源（纯函数，无 Minecraft 依赖）。
 *
 * <h3>为什么单独抽出来</h3>
 * 这条映射有六个来源（温度 / 熔点 / 熔毁 / 引爆 / 激光部件 / 稳定度）和四级输出，
 * 如果写进方块实体的 {@code update()} 里，就只能进游戏一格一格配色去试。
 * 抽成纯函数之后，离线自测可以直接 {@code evaluate(snapshot)} 断言每一级，
 * 而且改阈值时只动这一个文件。
 *
 * <h3>四级与配色</h3>
 * <table>
 *   <tr><th>等级</th><th>颜色</th><th>典型触发</th><th>转速</th></tr>
 *   <tr><td>0 正常</td><td>（熄灭）</td><td>—</td><td>0</td></tr>
 *   <tr><td>1 警告</td><td>黄</td><td>温度超过熔点 85%、透镜耐久低、稳定度偏低</td><td>10</td></tr>
 *   <tr><td>2 危急</td><td>橙</td><td>达到熔点、冷凝胶不足</td><td>30</td></tr>
 *   <tr><td>3 熔毁</td><td>红</td><td>进入熔毁 / 引爆器已启动 / 堆芯已熔毁 / 透镜将断</td><td>90</td></tr>
 * </table>
 *
 * <p>同级冲突时**按检查顺序取先出现的**（越靠前越"根因"）：熔毁 &gt; 引爆 &gt; 进入临界
 * &gt; 激光部件 &gt; 温度 &gt; 稳定度。比如温度刚到熔点、同时 OL 冷凝胶见底，
 * 报的是"达到熔点"（橙），因为那是玩家第一件要处理的事。</p>
 */
public final class AlertRules {

    // ==================== 等级 ====================
    public static final int OK = 0;
    public static final int WARN = 1;
    public static final int HIGH = 2;
    public static final int CRIT = 3;

    // ==================== 配色（RGB，直接进 AlarmConfig.color） ====================
    /** 正常：绿。灯在这种状态下是**熄灭**的，这个颜色只给 GUI / 预览用 */
    public static final int COLOR_OK = 0x2ECC71;
    public static final int COLOR_WARN = 0xFFD23F;
    public static final int COLOR_HIGH = 0xFF8A00;
    public static final int COLOR_CRIT = 0xFF1A1A;

    // ==================== 转速（rpm）：越严重转得越快 ====================
    public static final int RPM_OK = 0;
    public static final int RPM_WARN = 10;
    public static final int RPM_HIGH = 30;
    public static final int RPM_CRIT = 90;

    private static final AlertSignal OK_SIGNAL = new AlertSignal(OK, "ok", "");

    private AlertRules() {
    }

    // ==================== 对外 ====================

    /**
     * 判定一份快照的告警等级。
     *
     * <p>屏幕没连数据源（{@code !linked}）时不报警 —— 那只是"还没绑核心"，
     * 不是故障；否则刚放下的灯就会一直红着。</p>
     */
    public static AlertSignal evaluate(DfcSnapshot d) {
        if (d == null || !d.linked) {
            return OK_SIGNAL;
        }
        AlertSignal best = OK_SIGNAL;
        best = worse(best, signal(d.destroyed, CRIT, "destroyed", ""));
        best = worse(best, signal(d.detonation, CRIT, "detonation",
                d.detonationTimer > 0 ? String.valueOf(d.detonationTimer) : ""));
        boolean collapsing = d.finalPhase || d.collapsing > 0.0D;
        best = worse(best, signal(collapsing, CRIT, "collapse", ""));

        // 激光部件（SL 透镜 / OL 冷凝胶 / PL 电量）—— 取最严重的那一台
        best = worse(best, fromLasers(d));

        // 温度：85% 熔点 = 黄，到熔点 = 橙
        if (d.meltingPoint > 0) {
            double ratio = d.temperature / (double) d.meltingPoint;
            if (ratio >= 1.0D) {
                best = worse(best, new AlertSignal(HIGH, "melt",
                        DfcFormat.temperature(d.temperature)));
            } else if (ratio >= 0.85D) {
                best = worse(best, new AlertSignal(WARN, "hot",
                        DfcFormat.temperature(d.temperature)));
            }
        }

        // 稳定度：只在"堆芯在线 + 确实有稳定器在攒"时判。
        //   ★ 只看 online() 是不行的：刚点着的那一刻 stabilization 还是 0，
        //     会立刻报黄；停堆时它也不掉回 0，反而一直报。两个条件一起筛才准。
        if (ScreenLayout.online(d) && d.stabilizers > 0 && d.stabilization > 0.0D) {
            int st = ScreenLayout.stabLevel(d);
            if (st >= 2) {
                best = worse(best, new AlertSignal(HIGH, "stab",
                        DfcFormat.stabilization(d.stabilization)));
            } else if (st >= 1) {
                best = worse(best, new AlertSignal(WARN, "stab",
                        DfcFormat.stabilization(d.stabilization)));
            }
        }
        return best;
    }

    /** 单独判一组激光部件（激光屏之外的屏也可能带 lasers，统一走这里） */
    public static AlertSignal fromLasers(DfcSnapshot d) {
        AlertSignal best = OK_SIGNAL;
        if (d == null || d.lasers == null) {
            return best;
        }
        for (int i = 0; i < d.lasers.size(); i++) {
            LaserInfo lab = d.lasers.get(i);
            if (lab == null || lab.warn < LaserInfo.WARN_YELLOW) {
                continue;
            }
            int lv = levelOfLaserWarn(lab.warn);
            // 明细带上"哪个部件 + 什么问题"：聊天栏只说"激光部件告警"等于没说
            String detail = (lab.warnHead.isEmpty() ? "" : lab.warnHead + " ")
                    + (lab.warnText.isEmpty() ? lab.label : lab.warnText);
            best = worse(best, new AlertSignal(lv, "laser", detail));
        }
        return best;
    }

    /** {@link LaserInfo#WARN_*} → 四级 */
    public static int levelOfLaserWarn(int warn) {
        switch (warn) {
            case LaserInfo.WARN_RED:
                return CRIT;
            case LaserInfo.WARN_ORANGE:
                return HIGH;
            case LaserInfo.WARN_YELLOW:
                return WARN;
            default:
                return OK;
        }
    }

    public static int colorFor(int level) {
        switch (level) {
            case CRIT:
                return COLOR_CRIT;
            case HIGH:
                return COLOR_HIGH;
            case WARN:
                return COLOR_WARN;
            default:
                return COLOR_OK;
        }
    }

    public static int rpmFor(int level) {
        switch (level) {
            case CRIT:
                return RPM_CRIT;
            case HIGH:
                return RPM_HIGH;
            case WARN:
                return RPM_WARN;
            default:
                return RPM_OK;
        }
    }

    /** 正常时灯是灭的（真实警报器待机不亮），告警才点亮 */
    public static boolean litFor(int level) {
        return level > OK;
    }

    // ==================== 内部 ====================

    /** 只在 true 时造信号，省一堆 if */
    private static AlertSignal signal(boolean cond, int level, String key, String detail) {
        return cond ? new AlertSignal(level, key, detail) : OK_SIGNAL;
    }

    /** 取更严重的那个；同级保留先到的（调用顺序即优先级） */
    private static AlertSignal worse(AlertSignal cur, AlertSignal next) {
        return next.level > cur.level ? next : cur;
    }
}
