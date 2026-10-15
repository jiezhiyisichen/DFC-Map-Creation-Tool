package com.jiezhimod.item;

/**
 * 喷气背包的热量 / 飞行状态机（纯逻辑，不碰 World / Player，方便离线自测）。
 *
 * 设计成"纯数值 + 纯函数"是为了让 18 秒上限、过热锁定、降温速率这些
 * 最容易出错的边界条件都能在 SelfTest 里直接断言，不用开游戏。
 *
 * 状态机：
 * <pre>
 *   IDLE ──按住右键且未过热──> FIRING ──松开/过热──> COOLING
 *     ^                            │                   │
 *     └────────── 热量降回 0 ──────┴───────────────────┘
 * </pre>
 *
 * 关键约定：**热量只在"喷气中"累积，只在"没按住右键"时下降**。
 * 过热（热量打满）后进入 LOCKED，必须等热量降到 {@link #UNLOCK_HEAT} 以下才能再次喷气，
 * 中途按住右键也不会立刻恢复 —— 这就是"过热冷却"的节奏感。
 */
public final class JetHeat {

    /** 一次满燃料喷气可飞行的最长时间（秒） */
    public static final double MAX_BURN_SECONDS = 18.0D;
    /** 换算成 tick：18 秒 * 20 tick */
    public static final int MAX_HEAT = (int) (MAX_BURN_SECONDS * 20.0D);

    /**
     * 过热后需要降到这个热量才能重新喷气（占 MAX_HEAT 的比例）。
     * 留 30% 缓冲，玩家必须真的停下来冷却一会儿，而不是抖一下就能续飞。
     */
    public static final int UNLOCK_HEAT = (int) (MAX_HEAT * 0.30D);

    /** 喷气时每 tick 累积的热量（即"满热量 = 18 秒"） */
    public static final int HEAT_PER_TICK = 1;

    /**
     * 松开右键后的降温速率（tick/热量）。
     * 取 2 表示"停下来 9 秒（360/40）从满热降到 30%"，大致与 18 秒喷气时长对称，
     * 玩家能形成"飞 18 秒 -> 歇 9 秒"的节奏。
     */
    public static final int COOL_PER_TICK = 2;

    public enum State {
        /** 未喷气，热量在降 */
        COOLING,
        /** 正在喷气 */
        FIRING,
        /** 过热锁定，必须冷却到 UNLOCK_HEAT 以下 */
        OVERHEATED
    }

    private State state = State.COOLING;
    private int heat = 0;

    public State getState() {
        return this.state;
    }

    public int getHeat() {
        return this.heat;
    }

    /** 热量占比 0..1，供热量条渲染用 */
    public float getHeatRatio() {
        return MAX_HEAT <= 0 ? 0.0F : (float) this.heat / (float) MAX_HEAT;
    }

    /**
     * 推进一个 tick。
     *
     * @param wantFire 玩家这一 tick 是否按住右键（且装备齐全）
     * @return 推进后的状态
     */
    public State tick(boolean wantFire) {
        if (this.state == State.OVERHEATED) {
            // 过热锁定期间：只降温，按住右键也不给喷
            this.heat = Math.max(0, this.heat - COOL_PER_TICK);
            if (this.heat <= UNLOCK_HEAT) {
                this.state = State.COOLING;
            }
            return this.state;
        }

        if (wantFire) {
            this.heat = Math.min(MAX_HEAT, this.heat + HEAT_PER_TICK);
            if (this.heat >= MAX_HEAT) {
                // 打满 = 过热，立刻锁定
                this.state = State.OVERHEATED;
            } else {
                this.state = State.FIRING;
            }
        } else {
            // 松开右键随时降温（含从 FIRING 直落 COOLING）
            this.heat = Math.max(0, this.heat - COOL_PER_TICK);
            this.state = State.COOLING;
        }
        return this.state;
    }

    /**
     * 能否立即开始喷气。
     *
     * OVERHEATED 时必须已降到 UNLOCK_HEAT 以下才允许；
     * 这条同时用于 UI 提示（"过热中"）和实际起飞判定，两者永远一致。
     */
    public boolean canFire() {
        return this.state != State.OVERHEATED;
    }

    /** 是否正在喷气（用于是否施加升力 / 是否喷粒子） */
    public boolean isFiring() {
        return this.state == State.FIRING;
    }

    public boolean isOverheated() {
        return this.state == State.OVERHEATED;
    }

    /** 剩余可喷气时间（秒）；已过热返回 0 */
    public double remainingSeconds() {
        if (this.state == State.OVERHEATED) {
            return 0.0D;
        }
        return this.heat / (double) HEAT_PER_TICK / 20.0D;
    }

    // ==================== 便于自测 ====================

    public void setHeat(int heat) {
        this.heat = Math.max(0, Math.min(MAX_HEAT, heat));
    }

    public void setState(State state) {
        this.state = state == null ? State.COOLING : state;
    }

    public void reset() {
        this.heat = 0;
        this.state = State.COOLING;
    }
}
