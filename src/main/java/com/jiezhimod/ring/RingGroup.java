package com.jiezhimod.ring;

import io.netty.buffer.ByteBuf;

/**
 * 一组**连在一起的灯圈**（服务端权威状态）。
 *
 * <h3>组是怎么来的</h3>
 * 相邻（六面）的灯圈方块属于同一组 —— 放置 / 破坏任一格时整组重算
 * （洪水填充，见 {@link com.jiezhimod.ring.RingGroupManager}）。
 * 组内所有格子共享同一份 {@link RingState}，所以"激活一个 = 激活整组"。
 *
 * <h3>为什么只有一份状态、没有 per-格 的东西</h3>
 * 需求就是"单 / 多方块结构，一起变色一起闪"。per-格 差异（比如逐段点亮那种跑马灯）
 * 留给之后的旋转警报灯去做 —— 到时候给组加一个"相位偏移"字段即可，不用推翻这份结构。
 *
 * <p>{@code members} 只存 {@code long}（{@code BlockPos#asLong}），
 * 离线自测不用构造世界也能验证成员管理。</p>
 */
public final class RingGroup {

    /** 一组最多多少格。灯圈网络大到上千格时洪水填充会卡主线程，这里硬顶住 */
    public static final int MAX_MEMBERS = 1024;

    /** 组所在维度（不同维度的同名组互不相干） */
    public final int dim;
    /** 组标识：成员里 packed-pos 最小的那一格（放置 / 拆除都会导致它变化，但同一瞬间是稳定的） */
    public final long key;
    /** 组内所有格子的 packed 坐标 */
    public final long[] members;

    /** 组的当前状态（参数快照，见 RingState 的说明） */
    private RingState state = new RingState();
    /** 状态是"世界时间第几刻"设置的：服务端算"此刻颜色/亮灭"全靠它 */
    private long stamp;
    /** 版本号：状态每次变化 +1，方块实体靠比较它决定要不要重新广播 */
    private int version;

    public RingGroup(int dim, long key, long[] members) {
        this.dim = dim;
        this.key = key;
        this.members = members;
    }

    public RingState state() {
        return this.state;
    }

    /** 世界时间第几刻设置的这份状态 */
    public long stamp() {
        return this.stamp;
    }

    public int version() {
        return this.version;
    }

    /**
     * 换状态（服务端）。elapsed 字段一律清零 —— 快照的语义就是"此刻刚拍"。
     *
     * @return 状态是否真的变了（调用方据此打日志）
     */
    public boolean setState(RingState next, long now) {
        if (next == null || next.sameAs(this.state)) {
            return false;
        }
        next.colorFadeElapsed = 0;
        next.blinkElapsed = 0;
        this.state = next;
        this.stamp = now;
        this.version++;
        return true;
    }

    /**
     * **此刻**的状态快照：把 elapsed 按"已经走了几刻"补上。
     * 控制台接力（从当前颜色继续渐变）与红石亮灭判断都从这里拿。
     */
    public RingState snapshot(long now) {
        RingState s = this.state.copy();
        long dt = Math.max(0L, now - this.stamp);
        if (s.colorFadeDur > 0) {
            s.colorFadeElapsed = (int) Math.min(s.colorFadeDur, dt);
        }
        if (s.blink) {
            s.blinkElapsed = (int) Math.min(Integer.MAX_VALUE, dt);
        }
        return s;
    }

    /** 此刻算不算"亮"（方块实体用来翻 POWERED / 光照） */
    public boolean litAt(long now) {
        return this.state.litAt(Math.max(0L, now - this.stamp));
    }

    /**
     * 此刻的亮度系数（{@link RingState#brightAt} 的世界时间版本）。
     *
     * <p>方块实体拿它换算成 0~15 的**光照档位**写在方块状态里 ——
     * 这是"淡入淡出 / 闪烁真的看得见"的关键：tint 只在区块重烘焙时采样，
     * 而方块状态一变就会重烘焙 + 重算光照，两条路一起走。</p>
     */
    public double brightAt(long now) {
        return this.state.brightAt(Math.max(0L, now - this.stamp));
    }

    // ==================== 网络序列化（快照整份发给客户端） ====================

    public void writeTo(ByteBuf buf) {
        buf.writeInt(this.dim);
        buf.writeLong(this.key);
        buf.writeInt(this.version);
        this.state.writeTo(buf);
    }

    public static RingState readState(ByteBuf buf) {
        buf.readInt();
        buf.readLong();
        buf.readInt();
        RingState s = new RingState();
        s.readFrom(buf);
        return s;
    }
}
