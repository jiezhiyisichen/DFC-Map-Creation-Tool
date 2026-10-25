package com.jiezhimod.dfc;

/**
 * 激光状态显示器的**版面、配色与阈值**（纯函数，无 AWT、无 Minecraft 依赖）。
 *
 * 【这一页长什么样】四台激光，2×2 摆开。每个格子：
 *   左上角一块小图（就是激光器本身，开启时从图里射出光束）
 *   右边一行类型短名（SL1 / PL2 / OL1 …，颜色按类型区分）
 *   下面 5 行「名称 ………… 数值」，每行下面一条数据条（和自定义屏同款）
 * 底部留一条 16 像素高的告警带，只有在"该换透镜 / 冷凝胶不够"时才出现。
 *
 * 【为什么把阈值放这里】冷凝胶"黄 / 橙 / 红"的分档、透镜耐久"低于 4 报红"，
 * 这些是最容易被随手改错的数字，而且离线自测能直接断言边界。
 */
public final class LaserLayout {

    // ==================== 槽位与格子 ====================

    /** 一块屏最多挂几台激光（需求："最多 4 个激光状态"） */
    public static final int SLOTS = 4;

    /** 格子宽高（逻辑像素） */
    public static final int CELL_W = 124;
    public static final int CELL_H = 74;

    /** 2×2 格子的左上角坐标 */
    public static final int[] CELL_X = {2, 130, 2, 130};
    public static final int[] CELL_Y = {22, 22, 97, 97};

    /** 格子里小激光图的包围盒（相对格子） */
    public static final int ICON_X = 3;
    public static final int ICON_Y = 3;
    public static final int ICON_W = 36;
    public static final int ICON_H = 24;

    /** 类型短名的基线（相对格子） */
    public static final int NAME_X = 42;
    public static final int NAME_Y = 13;

    /** 第一行读数的基线偏移，以及行距 */
    public static final int METRIC_TOP = 22;
    public static final int METRIC_STEP = 10;
    /**
     * 读数区的左边界（相对格子）。
     *
     * 必须让开左边那张小图（宽 {@link #ICON_W} = 36，从 3 开始）——
     * 第一行读数的基线与小图的下缘在同一高度，横向错开才不会糊在一起。
     */
    public static final int METRIC_X = 42;
    /** 数值右对齐的右边界（相对格子） */
    public static final int METRIC_RIGHT = CELL_W - 5;

    /** 底部告警带 */
    public static final int BANNER_X = 4;
    public static final int BANNER_Y = 172;
    public static final int BANNER_W = 248;
    public static final int BANNER_H = 16;

    // ==================== 阈值 ====================

    /**
     * 冷凝胶剩余分档（0~1 比值）。
     *
     * 【为什么三档而不是两档】这一项是"还能撑多久"的连续量：
     * 掉到一半就该补货（黄）、四分之一基本撑不到下一炉（橙）、
     * 一成以下随时熄火（红）。只分"够/不够"会让玩家在橙区毫无准备。
     */
    public static final double COLD_YELLOW = 0.50D;
    public static final double COLD_ORANGE = 0.25D;
    public static final double COLD_RED = 0.10D;

    /** 透镜耐久（百分比）：低于 20% 提醒，低于 4% 报红并要求立即更换 */
    public static final double LENS_YELLOW = 20.0D;
    public static final double LENS_RED = 4.0D;

    private LaserLayout() {
    }

    // ==================== 类型 → 颜色 / 短名前缀 ====================

    /** 稳定器 SL：黄色 */
    public static final int C_SL = 0xFFFFC13D;
    /** 发射器 PL（加热用）：红色 */
    public static final int C_PL = 0xFFFF5A4A;
    /** 接收器 OL：浅蓝 */
    public static final int C_OL = 0xFF7FD2FF;
    /** 燃料喷射器：白 */
    public static final int C_INJ = 0xFFE9EFF5;
    /** 其它部件：冷却类蓝、加热类红、其余白 */
    public static final int C_COOL = 0xFF5AB4FF;
    public static final int C_HEAT = 0xFFE8604A;
    public static final int C_OTHER = 0xFFCFDAE4;

    /** 类型短名（弹窗、列表里也用） */
    public static String labelPrefix(int kind) {
        switch (kind) {
            case LaserInfo.KIND_SL: return "SL";
            case LaserInfo.KIND_PL: return "PL";
            case LaserInfo.KIND_OL: return "OL";
            case LaserInfo.KIND_INJ: return "INJ";
            case LaserInfo.KIND_GEN: return "L";
            default: return "";
        }
    }

    /**
     * 名字颜色。需求点名了三种（SL 黄 / PL 红 / OL 浅蓝 / 喷射器白），
     * 其它 DFC 激光部件按"有冷却功能 = 蓝、加热 = 红、其余 = 白"。
     */
    public static int nameColor(int kind, boolean cooling, boolean heating) {
        switch (kind) {
            case LaserInfo.KIND_SL: return C_SL;
            case LaserInfo.KIND_PL: return C_PL;
            case LaserInfo.KIND_OL: return C_OL;
            case LaserInfo.KIND_INJ: return C_INJ;
            default:
                if (cooling) {
                    return C_COOL;
                }
                return heating ? C_HEAT : C_OTHER;
        }
    }

    /** 类型的中文名（弹窗与列表里用） */
    public static String kindName(int kind) {
        switch (kind) {
            case LaserInfo.KIND_SL: return "稳定器";
            case LaserInfo.KIND_PL: return "发射器";
            case LaserInfo.KIND_OL: return "接收器";
            case LaserInfo.KIND_INJ: return "燃料喷射器";
            case LaserInfo.KIND_GEN: return "激光部件";
            default: return "空";
        }
    }

    // ==================== 阈值判定 ====================

    /** 冷凝胶剩余 → 警告等级（{@link LaserInfo#WARN_*}） */
    public static int coldWarn(double ratio) {
        if (ratio < COLD_RED) {
            return LaserInfo.WARN_RED;
        }
        if (ratio < COLD_ORANGE) {
            return LaserInfo.WARN_ORANGE;
        }
        if (ratio < COLD_YELLOW) {
            return LaserInfo.WARN_YELLOW;
        }
        return LaserInfo.WARN_NONE;
    }

    /** 透镜耐久（百分比）→ 警告等级；拿不到耐久（&lt;0）不报警 */
    public static int lensWarn(double percent) {
        if (percent < 0.0D) {
            return LaserInfo.WARN_NONE;
        }
        if (percent < LENS_RED) {
            return LaserInfo.WARN_RED;
        }
        if (percent < LENS_YELLOW) {
            return LaserInfo.WARN_YELLOW;
        }
        return LaserInfo.WARN_NONE;
    }

    /** 警告等级 → 颜色（画名字、警示三角、告警带都用它） */
    public static int warnColor(int warn) {
        switch (warn) {
            case LaserInfo.WARN_RED: return 0xFFFF4B4B;
            case LaserInfo.WARN_ORANGE: return 0xFFFF8A2B;
            case LaserInfo.WARN_YELLOW: return 0xFFFFC13D;
            default: return 0;
        }
    }

    /** 警告等级 → 中文（弹窗里那句"冷凝胶不足 / 透镜即将烧毁"） */
    public static String warnWord(int warn) {
        switch (warn) {
            case LaserInfo.WARN_RED: return "危急";
            case LaserInfo.WARN_ORANGE: return "警告";
            case LaserInfo.WARN_YELLOW: return "注意";
            default: return "正常";
        }
    }

    // ==================== 版面 ====================

    /** 第 i 个格子的左边界（越界夹到最后一个，避免画出面板） */
    public static int cellX(int i) {
        return CELL_X[Math.max(0, Math.min(SLOTS - 1, i))];
    }

    public static int cellY(int i) {
        return CELL_Y[Math.max(0, Math.min(SLOTS - 1, i))];
    }

    /** 第 i 行读数的基线 y（相对面板） */
    public static int metricY(int slot, int line) {
        return cellY(slot) + METRIC_TOP + line * METRIC_STEP;
    }

    /**
     * 按添加顺序编短名：同一类型内 SL1、SL2…，不同类型各自从 1 开始。
     *
     * 【为什么按类型分开编】需求原话"由于不可能每种激光恰好一个，所以根据添加的顺序标注"。
     * 若不分类型统一编号（1、2、3、4），玩家看到"3 号"根本不知道是哪一种；
     * 分开之后 SL2 一眼就是"第二台稳定器"。
     */
    public static String labelFor(int kind, int nth) {
        return labelPrefix(kind) + Math.max(1, nth);
    }
}
