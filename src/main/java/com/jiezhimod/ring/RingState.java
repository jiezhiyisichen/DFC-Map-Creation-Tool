package com.jiezhimod.ring;

import io.netty.buffer.ByteBuf;

/**
 * 一组灯圈（灯圈网络）的**共享状态快照**。
 *
 * <h3>为什么是"参数快照"而不是"每帧颜色"</h3>
 * 颜色在淡入淡出时每刻都在变，逐刻发包等于把一条 60 格的灯带做成 DDOS。
 * 所以服务端只广播**参数**（起始色 / 目标色 / 淡入时长 / 闪烁周期…），
 * 客户端拿到后用本地时钟自己算出"此刻该是什么颜色" —— 平滑、零流量、
 * 而且四个客户端算出来的必然一致（同一套纯函数）。
 *
 * <h3>时钟对齐</h3>
 * 快照里带的是"**拍快照那一刻已经走过了几刻**"（{@code *Elapsed}），不是绝对刻。
 * 客户端收到后记下"我收到时是本地第几刻"，之后
 * {@code 已走过刻数 = 快照里的 elapsed + (本地当前刻 - 本地收到刻)}。
 * 这样**服务端 / 客户端时钟差多少都无所谓**，不会出现"淡入在别的电脑上已经结束了"。
 *
 * <p>不引用任何 Minecraft 类（ByteBuf 除外），离线自测可以直接构造和断言。</p>
 */
public final class RingState {

    /** 关灯时的亮度（乘在颜色上）：留一点暗色轮廓，玩家才知道"这里有个灯圈，只是没开" */
    public static final double OFF_BRIGHT = 0.18D;

    // ---- 颜色 ----
    /** 变化起点色（淡入淡出的"从"） */
    public int colorFrom;
    /** 目标色（淡入淡出的"到"，也是无淡入时的最终颜色） */
    public int colorTo;
    /** 颜色过渡总时长（tick）；&lt;= 0 = 直接切换 */
    public int colorFadeDur;
    /** 拍快照时颜色过渡已经走了几刻 */
    public int colorFadeElapsed;

    // ---- 亮 / 灭 ----
    /** 稳态：true = 亮，false = 灭（闪烁由下面那组参数另算） */
    public boolean on;

    // ---- 闪烁 ----
    /** 是否处于闪烁中 */
    public boolean blink;
    /** 一个完整周期的长度（tick）：亮区 + 灭区 */
    public int blinkPeriod;
    /** 闪烁总时长（tick）；走完自动停回稳态 {@link #on} */
    public int blinkTotal;
    /** 每次亮起时的淡入时长（tick）；0 = 直接亮 */
    public int blinkFadeIn;
    /** 每次熄灭时的淡出时长（tick）；0 = 直接灭 */
    public int blinkFadeOut;
    /** 拍快照时闪烁已经走了几刻 */
    public int blinkElapsed;

    public RingState() {
        this.colorFrom = 0xFF3A3A;
        this.colorTo = 0xFF3A3A;
    }

    // ==================== 快照 ====================

    public RingState copy() {
        RingState o = new RingState();
        o.colorFrom = this.colorFrom;
        o.colorTo = this.colorTo;
        o.colorFadeDur = this.colorFadeDur;
        o.colorFadeElapsed = this.colorFadeElapsed;
        o.on = this.on;
        o.blink = this.blink;
        o.blinkPeriod = this.blinkPeriod;
        o.blinkTotal = this.blinkTotal;
        o.blinkFadeIn = this.blinkFadeIn;
        o.blinkFadeOut = this.blinkFadeOut;
        o.blinkElapsed = this.blinkElapsed;
        return o;
    }

    /** 整份拷进来（网络包 / 方块实体同步用；elapsed 也会一起拷） */
    public void copyFrom(RingState o) {
        if (o == null) {
            return;
        }
        this.colorFrom = o.colorFrom;
        this.colorTo = o.colorTo;
        this.colorFadeDur = o.colorFadeDur;
        this.colorFadeElapsed = o.colorFadeElapsed;
        this.on = o.on;
        this.blink = o.blink;
        this.blinkPeriod = o.blinkPeriod;
        this.blinkTotal = o.blinkTotal;
        this.blinkFadeIn = o.blinkFadeIn;
        this.blinkFadeOut = o.blinkFadeOut;
        this.blinkElapsed = o.blinkElapsed;
    }

    /** 参数有没有变（服务端决定要不要重新广播用） */
    public boolean sameAs(RingState o) {
        return o != null
                && this.on == o.on
                && this.colorFrom == o.colorFrom
                && this.colorTo == o.colorTo
                && this.colorFadeDur == o.colorFadeDur
                && this.colorFadeElapsed == o.colorFadeElapsed
                && this.blink == o.blink
                && this.blinkPeriod == o.blinkPeriod
                && this.blinkTotal == o.blinkTotal
                && this.blinkFadeIn == o.blinkFadeIn
                && this.blinkFadeOut == o.blinkFadeOut
                && this.blinkElapsed == o.blinkElapsed;
    }

    // ==================== 客户端：此刻的样子 ====================

    /**
     * 此刻应显示的颜色（已按亮 / 灭压暗）。
     *
     * @param ticksSince 本地收到快照之后又过了几刻
     */
    public int colorAt(long ticksSince) {
        return RingColor.dim(this.colorTo(this.colorFadeElapsed + ticksSince), this.brightAt(ticksSince));
    }

    /** 此刻颜色过渡走到的颜色（不压暗） */
    public int colorTo(long ticksSince) {
        if (this.colorFadeDur <= 0) {
            return this.colorTo;
        }
        double t = (this.colorFadeElapsed + ticksSince) / (double) this.colorFadeDur;
        return RingColor.lerp(this.colorFrom, this.colorTo, t);
    }

    /**
     * 此刻的亮度系数：1 = 全亮，{@link #OFF_BRIGHT} = 灭。
     * 闪烁时在两者之间平滑过渡（淡入 / 淡出各占周期的一段）。
     */
    public double brightAt(long ticksSince) {
        if (this.blink && this.blinkPeriod > 0) {
            long t = this.blinkElapsed + ticksSince;
            if (this.blinkTotal > 0 && t >= this.blinkTotal) {
                // 闪烁结束：回到稳态
                return this.on ? 1.0D : OFF_BRIGHT;
            }
            long phase = t % this.blinkPeriod;
            int in = clampNonNeg(this.blinkFadeIn);
            int out = clampNonNeg(this.blinkFadeOut);
            int onTicks;
            if (in == 0 && out == 0) {
                // 普通闪烁（重复关开）：亮半周期、灭半周期 —— 这才是玩家说的"直接重复关开"
                onTicks = Math.max(1, this.blinkPeriod / 2);
            } else {
                // 渐变闪烁：亮区 = 周期 - 淡入 - 淡出；两端吃满也要给亮区留至少 1 刻
                int avail = this.blinkPeriod - 1;
                if (in + out > avail) {
                    double k = avail / (double) (in + out);
                    in = (int) (in * k);
                    out = (int) (out * k);
                }
                onTicks = Math.max(1, this.blinkPeriod - in - out);
            }
            if (phase < in) {
                // 淡入
                return OFF_BRIGHT + (1.0D - OFF_BRIGHT) * (phase / (double) Math.max(1, in));
            }
            if (phase < in + onTicks) {
                return 1.0D;
            }
            if (out <= 0) {
                return OFF_BRIGHT;
            }
            // 淡出
            double k = (phase - in - onTicks) / (double) Math.max(1, out);
            return 1.0D - (1.0D - OFF_BRIGHT) * k;
        }
        return this.on ? 1.0D : OFF_BRIGHT;
    }

    /** 此刻算不算"亮"（方块实体用它决定方块状态 POWERED / 光照） */
    public boolean litAt(long ticksSince) {
        return this.brightAt(ticksSince) > (OFF_BRIGHT + 1.0D) * 0.5D;
    }

    private static int clampNonNeg(int v) {
        return v < 0 ? 0 : v;
    }

    // ==================== 网络序列化 ====================

    public void writeTo(ByteBuf buf) {
        buf.writeBoolean(this.on);
        buf.writeInt(this.colorFrom);
        buf.writeInt(this.colorTo);
        buf.writeShort(clampNonNeg(this.colorFadeDur));
        buf.writeShort(clampNonNeg(this.colorFadeElapsed));
        buf.writeBoolean(this.blink);
        buf.writeShort(clampNonNeg(this.blinkPeriod));
        buf.writeShort(clampNonNeg(this.blinkTotal));
        buf.writeShort(clampNonNeg(this.blinkFadeIn));
        buf.writeShort(clampNonNeg(this.blinkFadeOut));
        buf.writeShort(clampNonNeg(this.blinkElapsed));
    }

    public void readFrom(ByteBuf buf) {
        this.on = buf.readBoolean();
        this.colorFrom = buf.readInt();
        this.colorTo = buf.readInt();
        this.colorFadeDur = buf.readShort() & 0xFFFF;
        this.colorFadeElapsed = buf.readShort() & 0xFFFF;
        this.blink = buf.readBoolean();
        this.blinkPeriod = buf.readShort() & 0xFFFF;
        this.blinkTotal = buf.readShort() & 0xFFFF;
        this.blinkFadeIn = buf.readShort() & 0xFFFF;
        this.blinkFadeOut = buf.readShort() & 0xFFFF;
        this.blinkElapsed = buf.readShort() & 0xFFFF;
    }
}
