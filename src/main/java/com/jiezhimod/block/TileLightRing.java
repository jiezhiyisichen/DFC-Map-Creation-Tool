package com.jiezhimod.block;

import com.jiezhimod.ring.RingGroup;
import com.jiezhimod.ring.RingGroupManager;
import com.jiezhimod.ring.RingState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 一格灯圈的方块实体。
 *
 * <h3>它管三件事（三件事都极轻）</h3>
 * <ol>
 *   <li><b>服务端</b>：记自己在哪个组；组状态一变就把快照广播给客户端；
 *       按"此刻亮不亮"翻方块的 {@code POWERED}（这个属性决定发光强度，
 *       必须在服务端改，客户端才能拿到正确的光照）；</li>
 *   <li><b>客户端</b>：按收到的参数快照 + 本地时钟算出"此刻的颜色 / 亮灭"，
 *       淡入淡出期间每隔 2 刻请求一次重烘焙（tint 只在烘焙时采样，这是必须的代价）；</li>
 *   <li><b>红石直连</b>：上升沿整组亮、下降沿整组灭（见
 *       {@link BlockLightRing#neighborChanged}）。</li>
 * </ol>
 *
 * <h3>为什么颜色不在服务端逐刻算好再发</h3>
 * 一条 60 格的灯带，逐刻发 60 个包 = 白白烧网络。参数快照只有十几个字节，
 * 客户端自己插值 —— 见 {@link RingState} 的说明。
 */
public class TileLightRing extends TileEntity implements ITickable {

    /** NBT 键前缀 */
    private static final String K_GROUP = "GroupKey";
    private static final String K_HAS_GROUP = "HasGroup";

    /** 所在组的键（{@code BlockPos#asLong}，服务端用；客户端不需要） */
    private long groupKey;
    private boolean hasGroup;

    /** 同步过来的参数快照（客户端渲染用；服务端它是"最后一次广播出去的那份"） */
    private final RingState sync = new RingState();
    /** 客户端：收到快照时的本地世界时刻（消除两端时钟差，见 RingState 的说明） */
    private long recvTick;
    private boolean recvTickSet;

    /** 服务端：上次写进方块状态的亮度档位（0~15，-1 = 还没写过） */
    private int lastLevel = -1;

    /** 服务端：上次广播出去的组版本号 */
    private int sentVersion = Integer.MIN_VALUE;
    /** 上次的红石输入（做上升沿 / 下降沿） */
    private boolean lastRedstone;
    private boolean redstoneSeen;

    /** 客户端缓存：当前颜色 / 亮灭（自测与日志用，渲染 tint 也读它） */
    private int displayColor = 0xFFFFFF;
    private boolean litNow;
    /** 淡入淡出期间的重烘焙节流：每 2 刻刷一次（约 10fps 的渐变，肉眼足够顺滑） */
    private int renderCooldown;

    /** 重烘焙间隔（刻）：tint 只在烘焙时采样，渐变期间必须按这个节奏主动请求 */
    private static final int REBAKE_INTERVAL = 2;
    /** 上次请求重烘焙时用的颜色（只做诊断，便于把"没变"和"没刷"分开） */
    private int lastBakedColor = Integer.MIN_VALUE;

    public TileLightRing() {
    }

    // ==================== 客户端登记表（彩色光晕用） ====================

    /**
     * 客户端世界里所有灯圈的弱登记表。
     *
     * <h3>为什么要自己维护一份</h3>
     * {@link com.jiezhimod.client.ring.RingGlowRenderer} 每帧要给"视野里所有灯圈"
     * 画一层彩色光晕，而原版没有任何"列出世界里的某种方块实体"的接口。
     * 每帧去扫区块等于自杀，所以在 {@link #onLoad()} / {@link #invalidate()} 里自己记账。
     *
     * <p>只存客户端：服务端那份永远是空的（{@link #onLoad()} 里判了 {@code isRemote}），
     * 所以不会把服务端的世界对象泄漏进渲染线程。</p>
     */
    private static final java.util.Set<TileLightRing> CLIENT_ALL =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.WeakHashMap<TileLightRing, Boolean>()));

    /** 客户端渲染用：世界里现有的灯圈（可能含世界切换瞬间的残影，调用方要判 isInvalid） */
    public static java.util.Collection<TileLightRing> clientAll() {
        return CLIENT_ALL;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.world != null && this.world.isRemote) {
            CLIENT_ALL.add(this);
        }
    }

    @Override
    public void invalidate() {
        CLIENT_ALL.remove(this);
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        CLIENT_ALL.remove(this);
        super.onChunkUnload();
    }

    // ==================== 组归属（服务端） ====================

    /** 洪水填充算完组之后回填（见 RingGroupManager#rebuild） */
    public void setGroupKey(long key) {
        if (!this.hasGroup || this.groupKey != key) {
            this.groupKey = key;
            this.hasGroup = true;
            // 组可能换了一个（拆桥把网络重排）：强制重新广播一次
            this.sentVersion = Integer.MIN_VALUE;
            this.markDirty();
        }
    }

    public boolean hasGroup() {
        return this.hasGroup;
    }

    // ==================== 红石直连 ====================

    /**
     * 记录一次红石输入。
     *
     * @return 电平是否发生变化（调用方据此决定要不要重建组）
     */
    public boolean onRedstoneInput(boolean powered) {
        if (!this.redstoneSeen) {
            this.redstoneSeen = true;
            this.lastRedstone = powered;
            return false;   // 加载后的第一次读数不算边沿
        }
        if (powered == this.lastRedstone) {
            return false;
        }
        this.lastRedstone = powered;
        if (this.world != null && !this.world.isRemote) {
            RingGroup g = RingGroupManager.of(this.world).groupAt(this.pos);
            if (g != null && !g.state().blink) {
                RingState next = g.snapshot(this.world.getTotalWorldTime());
                next.blink = false;
                next.blinkPeriod = 0;
                next.blinkTotal = 0;
                next.blinkFadeIn = 0;
                next.blinkFadeOut = 0;
                next.on = powered;
                if (g.setState(next, this.world.getTotalWorldTime())) {
                    // 运行期必须自己发（notifyBlockUpdate 只带方块状态），否则客户端颜色不动
                    com.jiezhimod.ring.RingNet.broadcast(this.world, g);
                }
            }
        }
        return true;
    }

    // ==================== 每刻 ====================

    @Override
    public void update() {
        if (this.world == null || this.pos == null) {
            return;
        }
        if (this.world.isRemote) {
            this.clientTick();
        } else {
            this.serverTick();
        }
    }

    /** 服务端：组状态一变就广播；亮灭变了就翻方块状态（光照） */
    private void serverTick() {
        RingGroupManager m = RingGroupManager.of(this.world);
        if (!this.hasGroup) {
            // 区块刚加载：方块实体还没登记进任何组，补一次（GroupKey 在 NBT 里也存了，
            // 但登记表是内存的，读档后必须重建）
            m.rebuild(this.world, this.pos);
            if (!this.hasGroup) {
                return;
            }
        }
        RingGroup g = m.groupAt(this.pos);
        if (g == null) {
            this.hasGroup = false;
            return;
        }
        // ★★ 亮度档位写进**方块状态**（不只是 tint）。
        //   tint 只在区块重烘焙时采样，光靠它表达亮度，区块不重建画面就不动；
        //   状态一变 ⇒ 客户端重烘焙 + 光照重算 ⇒ 灯自己和周围一起变亮/变暗。
        long now = this.world.getTotalWorldTime();
        int level = levelFor(brightOf(g, now));
        if (level != this.lastLevel) {
            this.lastLevel = level;
            IBlockState st = this.world.getBlockState(this.pos);
            if (st.getBlock() instanceof BlockLightRing
                    && st.getValue(BlockLightRing.LEVEL).intValue() != level) {
                // flag 3：客户端拿到新状态（光照 + 重烘焙）+ 通知邻居（红石比较器之类）
                // 静默期：这 6 个邻居的 neighborChanged 不该再重建组（见 RingGroupManager）
                RingGroupManager.beginQuiet();
                try {
                    this.world.setBlockState(this.pos,
                            st.withProperty(BlockLightRing.LEVEL, Integer.valueOf(level)), 3);
                } finally {
                    RingGroupManager.endQuiet();
                }
                dbgFlip(level);
            }
        }
        if (g.version() != this.sentVersion) {
            this.sentVersion = g.version();
            // 快照语义是"此刻刚拍"：把组状态原样搬进来（elapsed 已在 setState 里清零）
            RingState s = g.state();
            this.sync.on = s.on;
            this.sync.colorFrom = s.colorFrom;
            this.sync.colorTo = s.colorTo;
            this.sync.colorFadeDur = s.colorFadeDur;
            this.sync.colorFadeElapsed = 0;
            this.sync.blink = s.blink;
            this.sync.blinkPeriod = s.blinkPeriod;
            this.sync.blinkTotal = s.blinkTotal;
            this.sync.blinkFadeIn = s.blinkFadeIn;
            this.sync.blinkFadeOut = s.blinkFadeOut;
            this.sync.blinkElapsed = 0;
            this.markDirty();
            // 【不要再叫 notifyBlockUpdate】它只同步方块状态，快照是 MessageRingState 发的；
            // 每格发一次 SPacketBlockChange 纯属浪费（一组 60 格就是 60 个包）。
        }
    }

    /** 客户端：按参数算此刻的颜色，淡入淡出期间节流地请求重烘焙 */
    private void clientTick() {
        if (!this.recvTickSet) {
            // ★ 兜底：区块重载（或读档）后才建出来的方块实体，NBT 里带着参数快照，
            //   但没有"收到时刻"这个运行时字段。以前这里直接 return，结果是
            //   重载之后这格灯圈的颜色/闪烁**永远不动**（只剩存档里那份静态颜色）。
            //   把"此刻"当成收到时刻，参数快照就能继续往前走。
            this.recvTick = this.world.getTotalWorldTime();
            this.recvTickSet = true;
            this.lastBakedColor = Integer.MIN_VALUE;
        }
        long dt = this.world.getTotalWorldTime() - this.recvTick;
        this.litNow = this.sync.litAt(dt);
        int c = this.sync.colorAt(dt);
        if (c == this.displayColor) {
            return;
        }
        this.displayColor = c;
        if (this.renderCooldown > 0) {
            this.renderCooldown--;
            return;
        }
        this.requestRebake();
    }

    /**
     * 请求重烘焙这一小片（tint 只在烘焙时采样，这是必须的代价）。
     *
     * <p>【别再在 {@link #displayColor()} 里写 displayColor】那个方法被 tint 调用，
     * 写回会让"颜色变了没有"这个判据永远为假 —— 主线程刚推进的颜色被渲染线程
     * 用同一个值覆盖，之后 {@code clientTick} 一比就相等，于是**再也不会请求重烘焙**，
     * 画面卡在某一帧的颜色上（v4.4 实机"淡入淡出/闪烁没效果"就是这么来的）。</p>
     */
    private void requestRebake() {
        this.lastBakedColor = this.displayColor;
        this.renderCooldown = REBAKE_INTERVAL;
        if (this.world == null) {
            return;
        }
        this.world.markBlockRangeForRenderUpdate(
                this.pos.getX() - 1, this.pos.getY() - 1, this.pos.getZ() - 1,
                this.pos.getX() + 1, this.pos.getY() + 1, this.pos.getZ() + 1);
    }

    /**
     * 客户端收到状态包后调用（{@link com.jiezhimod.network.msg.MessageRingState}）。
     *
     * <p>【为什么要自己收包】见 {@code MessageRingState} 的类注释：
     * {@code notifyBlockUpdate} 只同步方块状态、方块实体包只在区块加载时发一次，
     * 运行期改了颜色客户端是收不到的（v4.4 第一版就是这个症状：灯圈一直白）。</p>
     *
     * @param state     服务端下发的那份参数快照
     * @param clientNow 客户端收到时的本地世界刻（消除两端时钟差，见 RingState）
     */
    public void applySync(RingState state, long clientNow) {
        if (state == null) {
            return;
        }
        this.sync.copyFrom(state);
        this.sync.colorFadeElapsed = 0;
        this.sync.blinkElapsed = 0;
        this.recvTick = clientNow;
        this.recvTickSet = true;
        this.litNow = this.sync.litAt(0);
        this.displayColor = this.sync.colorAt(0);
        if (this.world != null && this.world.isRemote) {
            // tint 只在烘焙时采样：颜色变了立刻重烘焙这一小片
            this.renderCooldown = 0;
            this.requestRebake();
        }
        dbg("sync -> " + com.jiezhimod.ring.RingColor.toHex(this.displayColor)
                + (this.sync.on ? " (on)" : " (off)")
                + (this.sync.blink ? " blink" : "")
                + (this.sync.colorFadeDur > 0 ? " fade " + this.sync.colorFadeDur + "t" : ""));
    }

    // ==================== 渲染取色（客户端 tint 用） ====================

    /**
     * 此刻该显示的颜色（{@code IBlockColor} 每次烘焙都来问它）。
     *
     * <p>【只读】推进颜色的责任在 {@link #clientTick()}，这里绝不能写回字段，
     * 原因见 {@link #requestRebake()}。</p>
     */
    public int displayColor() {
        return this.displayColor;
    }

    /** 客户端 tint 的入口：取不到方块实体（ item 形态 / 区块还没同步）时给白色 */
    public static int tint(net.minecraft.world.IBlockAccess world, BlockPos pos) {
        if (world == null || pos == null) {
            return 0xFFFFFF;
        }
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileLightRing ? ((TileLightRing) te).displayColor() : 0xFFFFFF;
    }

    // ==================== NBT / 同步 ====================

    @Override
    public void readFromNBT(NBTTagCompound c) {
        super.readFromNBT(c);
        this.hasGroup = c.getBoolean(K_HAS_GROUP);
        this.groupKey = c.getLong(K_GROUP);
        if (c.hasKey("RingState")) {
            RingStateNbt.read(this.sync, c.getCompoundTag("RingState"));
            this.litNow = this.sync.on;
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        super.writeToNBT(c);
        c.setBoolean(K_HAS_GROUP, this.hasGroup);
        c.setLong(K_GROUP, this.groupKey);
        c.setTag("RingState", RingStateNbt.write(this.sync));
        return c;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound c = super.getUpdateTag();
        c.setTag("RingState", RingStateNbt.write(this.sync));
        return c;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 1, this.getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        this.readFromNBT(pkt.getNbtCompound());
        this.recvTick = this.world == null ? 0L : this.world.getTotalWorldTime();
        this.recvTickSet = true;
        this.litNow = this.sync.on;
        if (this.world != null && !this.world.isRemote) {
            return;
        }
        // 客户端：快照一到立刻刷一次颜色（不等下一刻）
        this.displayColor = this.sync.colorAt(0);
        if (this.world != null) {
            this.renderCooldown = 0;
            this.requestRebake();
        }
    }

    // ==================== 调试日志 ====================

    private static int dbgCount;
    private static int dbgFlipCount;

    /**
     * 排错日志：每台灯圈前 3 条全打（含颜色与亮灭），之后闭嘴。
     *
     * 【为什么要它】"灯圈不变色"这类问题，只看画面没法判断是
     * "包没到 / tint 没注册 / 颜色算错"三者中的哪一个；打一行颜色就能一眼分开。
     * 但也绝不能每刻打 —— 一条 60 格的灯带会瞬间刷爆日志（用户点名过这点）。
     */
    /**
     * 亮灭翻转日志：前 40 条（闪烁一次 10 转以内，足够看清"到底在不在翻"）。
     *
     * <p>"闪烁没效果"只有两种可能：**服务端压根没翻 POWERED**，或者**翻了但画面没刷**。
     * 打一行翻转日志就能一刀切开，不用再猜。</p>
     */
    private void dbgFlip(int level) {
        if (dbgFlipCount++ < 40) {
            com.jiezhimod.JiezhiMod.warn("[Ring] level -> " + level
                    + " @t=" + (this.world == null ? -1L : this.world.getTotalWorldTime()));
        }
    }

    /** 亮度系数 -> 0~15 档位。灭态（OFF_BRIGHT 及以下）一律 0，不留暗光 */
    private static int levelFor(double bright) {
        if (bright <= com.jiezhimod.ring.RingState.OFF_BRIGHT) {
            return 0;
        }
        if (bright >= 1.0D) {
            return 15;
        }
        double k = (bright - com.jiezhimod.ring.RingState.OFF_BRIGHT)
                / (1.0D - com.jiezhimod.ring.RingState.OFF_BRIGHT);
        return (int) Math.round(k * 15.0D);
    }

    /** 取"此刻亮度"，组没了就按灭处理 */
    private static double brightOf(RingGroup g, long now) {
        return g == null ? 0.0D : g.brightAt(now);
    }

    private static void dbg(String msg) {
        if (dbgCount++ < 60) {
            com.jiezhimod.JiezhiMod.warn("[Ring] " + msg);
        }
    }

    // ==================== RingState 的 NBT 适配 ====================

    /** RingState 本身不碰 NBT（保持纯逻辑），NBT 形式收在这里 */
    private static final class RingStateNbt {
        private static final String[] K = {
                "On", "CF", "CT", "CD", "CE", "Bk", "BP", "BT", "BI", "BO", "BE"};

        static NBTTagCompound write(RingState s) {
            NBTTagCompound c = new NBTTagCompound();
            c.setBoolean(K[0], s.on);
            c.setInteger(K[1], s.colorFrom);
            c.setInteger(K[2], s.colorTo);
            c.setInteger(K[3], s.colorFadeDur);
            c.setInteger(K[4], s.colorFadeElapsed);
            c.setBoolean(K[5], s.blink);
            c.setInteger(K[6], s.blinkPeriod);
            c.setInteger(K[7], s.blinkTotal);
            c.setInteger(K[8], s.blinkFadeIn);
            c.setInteger(K[9], s.blinkFadeOut);
            c.setInteger(K[10], s.blinkElapsed);
            return c;
        }

        static void read(RingState s, NBTTagCompound c) {
            s.on = c.getBoolean(K[0]);
            s.colorFrom = c.getInteger(K[1]);
            s.colorTo = c.getInteger(K[2]);
            s.colorFadeDur = c.getInteger(K[3]);
            s.colorFadeElapsed = c.getInteger(K[4]);
            s.blink = c.getBoolean(K[5]);
            s.blinkPeriod = c.getInteger(K[6]);
            s.blinkTotal = c.getInteger(K[7]);
            s.blinkFadeIn = c.getInteger(K[8]);
            s.blinkFadeOut = c.getInteger(K[9]);
            s.blinkElapsed = c.getInteger(K[10]);
        }
    }
}
