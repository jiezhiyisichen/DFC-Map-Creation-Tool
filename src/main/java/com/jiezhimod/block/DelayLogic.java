package com.jiezhimod.block;

import net.minecraft.util.EnumFacing;

import java.util.ArrayList;
import java.util.List;

/**
 * 红石延时器的纯逻辑层：单位换算、方向推导、文案。
 *
 * 不依赖 World / TileEntity，方便离线自测直接断言。
 *
 * 方向约定（关键）：输出方向一律以"信号从哪一面进来"为基准，和方块自身朝向无关，
 * 所以放下就能用，不用转方向。
 *
 *   inputDir = 从本方块指向"信号来源邻居"的方向（也就是信号穿进来的那一面）。
 *   through  = inputDir.getOpposite()，信号穿过去继续沿同一个世界方向前进。
 *   左 / 右   = 站在方块上顺着 through 望过去时的左手边 / 右手边。
 *
 * 例：信号从北面进来（inputDir = NORTH），through = SOUTH，
 *     则 左 = EAST、右 = WEST（面朝南时左手是东）。
 *
 * 信号从上方 / 下方进来时没有"左右"可言（竖直方向绕 Y 轴旋转无定义），
 * 此时向左 / 向右 / 三向统统退化成直通，不会抛异常。
 */
public final class DelayLogic {

    /** 时间单位：tick / 秒 / 分 */
    public static final int UNIT_TICK = 0;
    public static final int UNIT_SECOND = 1;
    public static final int UNIT_MINUTE = 2;

    /** 输出方向模式（全部相对于输入方向） */
    public static final int MODE_FRONT = 0;
    public static final int MODE_LEFT = 1;
    public static final int MODE_RIGHT = 2;
    public static final int MODE_THREE = 3;

    public static final int TICKS_PER_SECOND = 20;
    public static final int TICKS_PER_MINUTE = 1200;
    /** 单个数值框允许的最大输入值 */
    public static final int MAX_VALUE = 9999;
    /** 换算后允许的最大 tick 数（1200 000 tick = 1000 分钟） */
    public static final int MAX_TICKS = 1200000;

    private DelayLogic() {
    }

    public static int clampValue(int value) {
        if (value < 0) {
            return 0;
        }
        return value > MAX_VALUE ? MAX_VALUE : value;
    }

    public static int clampUnit(int unit) {
        return (unit >= UNIT_SECOND && unit <= UNIT_MINUTE) ? unit : UNIT_TICK;
    }

    public static int clampMode(int mode) {
        return (mode >= MODE_FRONT && mode <= MODE_THREE) ? mode : MODE_FRONT;
    }

    /** 数值 + 单位 -> tick；越界自动收敛，永不返回负数 */
    public static int toTicks(int value, int unit) {
        int mul = 1;
        if (unit == UNIT_SECOND) {
            mul = TICKS_PER_SECOND;
        } else if (unit == UNIT_MINUTE) {
            mul = TICKS_PER_MINUTE;
        }
        long ticks = (long) clampValue(value) * (long) mul;
        return ticks > MAX_TICKS ? MAX_TICKS : (int) ticks;
    }

    // ==================== 方向 ====================

    /** 序号 -> 方向；越界一律给 null（EnumFacing.getFront 会回绕，不能直接用） */
    public static EnumFacing facingByOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= EnumFacing.values().length) {
            return null;
        }
        return EnumFacing.values()[ordinal];
    }

    public static boolean isHorizontal(EnumFacing facing) {
        return facing != null && facing.getAxis().isHorizontal();
    }

    /** 信号穿过去继续前进的方向；无输入时返回 null */
    public static EnumFacing through(EnumFacing inputDir) {
        return inputDir == null ? null : inputDir.getOpposite();
    }

    /** 顺着信号行进方向看过去的左手边；输入竖直时退化成正对面 */
    public static EnumFacing left(EnumFacing inputDir) {
        EnumFacing t = through(inputDir);
        return isHorizontal(t) ? t.rotateYCCW() : t;
    }

    /** 顺着信号行进方向看过去的右手边；输入竖直时退化成正对面 */
    public static EnumFacing right(EnumFacing inputDir) {
        EnumFacing t = through(inputDir);
        return isHorizontal(t) ? t.rotateY() : t;
    }

    /** 当前模式下实际会输出红石信号的方向集合（元素含义：从本方块指向邻居） */
    public static List<EnumFacing> outputSides(EnumFacing inputDir, int mode) {
        List<EnumFacing> out = new ArrayList<EnumFacing>(3);
        if (inputDir == null) {
            return out;
        }
        EnumFacing t = through(inputDir);
        switch (clampMode(mode)) {
            case MODE_LEFT:
                addUnique(out, left(inputDir));
                break;
            case MODE_RIGHT:
                addUnique(out, right(inputDir));
                break;
            case MODE_THREE:
                addUnique(out, t);
                addUnique(out, left(inputDir));
                addUnique(out, right(inputDir));
                break;
            case MODE_FRONT:
            default:
                addUnique(out, t);
                break;
        }
        return out;
    }

    /**
     * side = 从本方块指向某个邻居的方向；判断该方向是否属于输出面。
     * 红石查询每秒可能跑很多次，这里避免建临时 List。
     */
    public static boolean isOutputSide(EnumFacing inputDir, int mode, EnumFacing side) {
        if (inputDir == null || side == null) {
            return false;
        }
        EnumFacing t = through(inputDir);
        switch (clampMode(mode)) {
            case MODE_LEFT:
                return side == left(inputDir);
            case MODE_RIGHT:
                return side == right(inputDir);
            case MODE_THREE:
                if (side == t) {
                    return true;
                }
                return isHorizontal(t) && (side == t.rotateYCCW() || side == t.rotateY());
            case MODE_FRONT:
            default:
                return side == t;
        }
    }

    /**
     * 从六个方向的功率值里挑出"信号来源方向"（数组下标 = EnumFacing.getIndex()）。
     *
     * 并列时优先取水平方向（玩家接线一般走侧面），再并列取序号小的——
     * 这样同一份世界状态永远得到同一个结果，不会每 tick 抖动。
     *
     * @return 全为 0 时返回 null（视为没有输入）
     */
    public static EnumFacing pickInputSide(int[] power) {
        if (power == null || power.length < EnumFacing.values().length) {
            return null;
        }
        EnumFacing best = null;
        int bestPower = 0;
        for (EnumFacing f : EnumFacing.values()) {
            int p = power[f.getIndex()];
            if (p <= 0) {
                continue;
            }
            if (p > bestPower) {
                best = f;
                bestPower = p;
            } else if (p == bestPower && best != null && beats(best, f)) {
                best = f;
            }
        }
        return best;
    }

    /** cand 是否比 cur 更适合当来源（水平优先，其次序号小） */
    private static boolean beats(EnumFacing cur, EnumFacing cand) {
        boolean curH = cur.getAxis().isHorizontal();
        boolean candH = cand.getAxis().isHorizontal();
        if (candH != curH) {
            return candH;
        }
        return cand.getIndex() < cur.getIndex();
    }

    private static void addUnique(List<EnumFacing> list, EnumFacing facing) {
        if (facing != null && !list.contains(facing)) {
            list.add(facing);
        }
    }

    // ==================== 文案 ====================

    /** 单位短名，直接贴在按钮上 */
    public static String unitName(int unit) {
        switch (clampUnit(unit)) {
            case UNIT_SECOND:
                return "sec";
            case UNIT_MINUTE:
                return "min";
            case UNIT_TICK:
            default:
                return "tick";
        }
    }

    /** 界面方向的 lang 后缀（上/下/北/南/东/西） */
    public static String facingKey(EnumFacing facing) {
        if (facing == null) {
            return "none";
        }
        switch (facing) {
            case UP:
                return "up";
            case DOWN:
                return "down";
            case NORTH:
                return "north";
            case SOUTH:
                return "south";
            case EAST:
                return "east";
            case WEST:
                return "west";
            default:
                return "none";
        }
    }

    /** 给用户看的延时描述，例如 1200 ticks (60.0 s) */
    public static String formatTicks(int ticks) {
        int t = Math.max(0, ticks);
        double seconds = t / (double) TICKS_PER_SECOND;
        if (t >= TICKS_PER_MINUTE) {
            return t + " ticks (" + trim(seconds) + " s / " + trim(t / (double) TICKS_PER_MINUTE) + " min)";
        }
        return t + " ticks (" + trim(seconds) + " s)";
    }

    private static String trim(double value) {
        String s = String.format(java.util.Locale.US, "%.2f", value);
        while (s.endsWith("0") && s.indexOf('.') >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
