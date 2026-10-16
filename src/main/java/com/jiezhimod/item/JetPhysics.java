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
     * 把玩家的**局部输入**（前后 / 左右）按朝向换算成**世界坐标**下的目标速度。
     *
     * 【为什么必须过这一步】玩家的 "W" 是"朝我视线方向前进"，不是"沿世界 +X 前进"。
     * 早期版本直接把 forward 塞进 motionX、把 strafe 塞进 motionZ ——
     * 结果无论面朝哪儿，喷气都往世界 +X 方向推，转身完全不起作用。
     *
     * 公式与 {@code EntityLivingBase.moveEntityWithHeading} 完全一致：
     * <pre>
     *   worldX += strafe * cos(yaw) - forward * sin(yaw)
     *   worldZ += forward * cos(yaw) + strafe * sin(yaw)
     * </pre>
     * 校验点（可直接离线断言）：yaw = 0（面朝 +Z/南）时按 W → 目标速度指向 +Z；
     * yaw = 90（面朝 -X/西）时按 W → 目标速度指向 -X。
     *
     * @param yawDeg  玩家 rotationYaw（度）
     * @param forward 前后输入 -1..1（W 为正）
     * @param strafe  左右输入 -1..1（D 为正）
     * @param rising  是否正在按跳跃键（给一点额外前进推力，做出"抬头加速"的姿态）
     * @return {目标 worldX 速度, 目标 worldZ 速度}
     */
    public static double[] worldTarget(double yawDeg, double forward, double strafe, boolean rising) {
        // 斜着走（W+D）两轴各 1 的话速度会变成 √2 倍，先夹成长度 1
        double len = Math.sqrt(forward * forward + strafe * strafe);
        if (len > 1.0D) {
            forward /= len;
            strafe /= len;
        }
        double f = forward * (FWD_SPEED + (rising ? RISE_BONUS : 0.0D));
        double s = strafe * STRAFE_SPEED;
        double yaw = Math.toRadians(yawDeg);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        return new double[]{s * cos - f * sin, f * cos + s * sin};
    }

    /**
     * 推进一 tick 的完整结果。
     *
     * @param current 玩家当前的速度 (vx, vy, vz)
     * @param yawDeg  朝向（度）—— 局部输入就是靠它转成世界方向的
     * @param forward 前后输入 -1..1
     * @param strafe  左右输入 -1..1
     * @param up      上升输入（按跳跃键时给 1，否则 0）
     * @param rising  是否正在上升（决定竖直目标取 RISE_V 还是 HOVER_V）
     * @return 新的速度 (vx, vy, vz)
     */
    public static double[] step(double[] current, double yawDeg, double forward, double strafe,
                                double up, boolean rising) {
        double[] target = worldTarget(yawDeg, forward, strafe, rising);
        double vx = steerHorizontal(current[0], target[0]);
        double vz = steerHorizontal(current[2], target[1]);
        double vy = steerVertical(current[1] + up * VERTICAL_ACCEL, rising);
        return new double[]{vx, vy, vz};
    }
}
