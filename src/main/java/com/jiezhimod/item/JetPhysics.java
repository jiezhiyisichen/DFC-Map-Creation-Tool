package com.jiezhimod.item;

/**
 * 喷气飞行的运动学（纯函数，便于离线自测）。
 *
 * 为什么不直接写 motionY：原版每 tick 的物理顺序是
 *   y += v  ->  v = (v - 0.08) * 0.98   （EntityLivingBase.travel）
 * 想得到"稳定悬停 / 缓慢爬升"必须反解出目标速度，
 * 写在这里可以用离线测试精确验证，而不必开游戏看手感。
 */
public final class JetPhysics {

    /** 每 tick 的重力增量（与原版 travel() 一致） */
    public static final double GRAVITY_PER_TICK = 0.08D;
    /** 竖直阻尼（与原版一致） */
    public static final double VERTICAL_DRAG = 0.98D;
    /** 水平阻尼（原版地面移动用 0.91，空中更接近这个值） */
    public static final double HORIZONTAL_DRAG = 0.91D;

    /** 匀速悬停所需的竖直速度：v 使 (v-0.08)*0.98 == v，解得 v = 0.08*0.98/0.02 = 3.92 */
    public static final double HOVER_V = 3.92D;

    /** 爬升时的竖直目标速度（比悬停高一些，上升才有推背感） */
    public static final double RISE_V = 4.55D;

    /** 向前推进的目标水平速度（格/tick），约等于原版疾跑 0.13 的 3 倍多 */
    public static final double FWD_SPEED = 0.46D;

    /** 侧移的目标水平速度（比前进弱，操作手感更像"喷气"而不是"漂移"） */
    public static final double STRAFE_SPEED = 0.34D;

    /** 上升时的最大爬升角对应的前进加速（抬头飞行给一点推力） */
    public static final double RISE_BONUS = 0.30D;

    /**
     * 把竖直速度拉向"稳定悬停/爬升"的目标值。
     *
     * 直接赋值会因重力的非线性产生高频抖动（一帧上一帧下），
     * 所以每 tick 只允许改变 {@link #VERTICAL_ACCEL}，得到平滑的加减速。
     */
    public static double steerVertical(double currentV, boolean rising) {
        double target = rising ? RISE_V : HOVER_V;
        double maxDelta = VERTICAL_ACCEL;
        double delta = target - currentV;
        if (delta > maxDelta) {
            delta = maxDelta;
        } else if (delta < -maxDelta) {
            delta = -maxDelta;
        }
        return currentV + delta;
    }

    /** 竖直每 tick 的最大加速度（0.28 约等于 0.9 格/s² 的爬升体感，手感不飘） */
    public static final double VERTICAL_ACCEL = 0.28D;

    /**
     * 把某个水平分量拉向目标速度。
     *
     * @param target 目标速度；0 表示该方向不推进（松开时用它来减速）
     */
    public static double steerHorizontal(double current, double target) {
        double maxDelta = HORIZONTAL_ACCEL;
        double delta = target - current;
        if (delta > maxDelta) {
            delta = maxDelta;
        } else if (delta < -maxDelta) {
            delta = -maxDelta;
        }
        return current + delta;
    }

    /** 水平每 tick 的最大加速度 */
    public static final double HORIZONTAL_ACCEL = 0.18D;

    /**
     * 推进一 tick 的完整结果。
     *
     * @param in      玩家本 tick 的输入：前后(-1/0/1)、左右(-1/0/1)、是否上升
     * @param current 玩家当前的速度 (vx, vy, vz)
     * @return 新的速度
     */
    public static double[] step(double[] current, double forward, double strafe,
                                double up, boolean rising) {
        double vx = current[0];
        double vy = current[1];
        double vz = current[2];

        // 上升键：额外给一点前进推力，做出"抬头加速"的姿态
        double fwd = forward * (FWD_SPEED + (rising ? RISE_BONUS : 0.0D));

        vx = steerHorizontal(vx, fwd);
        vz = steerHorizontal(vz, strafe * STRAFE_SPEED);
        vy = steerVertical(vy + up * VERTICAL_ACCEL, rising);

        return new double[]{vx, vy, vz};
    }
}
