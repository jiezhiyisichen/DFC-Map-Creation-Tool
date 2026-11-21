package com.jiezhimod.block;

import com.jiezhimod.alarm.AlarmConfig;
import com.jiezhimod.alarm.AlertRules;
import com.jiezhimod.alarm.AlertSignal;
import com.jiezhimod.dfc.DfcSnapshot;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * 旋转警示灯的方块实体：只存"长什么样"（颜色 / 转速 / 开关），不存相位。
 *
 * <h3>相位为什么按坐标算，而不是记一个"放置时刻"</h3>
 * HBM 的旋转灯存的是 {@code timeAdded}（放下那一刻的世界刻），渲染时用
 * {@code now - timeAdded} 当转角。那样每盏灯都多一个必须<b>精确同步到客户端</b>的字段，
 * 一旦网络包晚到 / 存档重载，相位就会跳一下。
 *
 * <p>这里改用 {@code hash(x, y, z)} 当固定相位偏移：不占存档、不用同步、
 * 重进世界也完全一致，而且天然让相邻的灯错开（不会整排同步闪成一条线）。</p>
 */
public class TileAlarmLight extends TileEntity implements ITickable {

    private final AlarmConfig cfg = new AlarmConfig();

    /** 坐标哈希出来的相位偏移（度），渲染时直接加在转角上 */
    private float phaseOffset;

    // ==================== 告警联动（v4.5） ====================

    /**
     * 绑定的**数据源**（一块 DFC 显示屏 / 激光显示屏的坐标）。{@code null} = 不联动，
     * 只受控制台 / 染料 / 红石摆布。
     *
     * <p>★ 联动期间**控制台的推送会被忽略**（见 {@link #applyConsoleConfig}）——
     * 否则控制台每次红石沿都会把颜色刷回统一色，告警就白做了。
     * 但绑定动作本身是玩家显式操作：重新用链接器绑控制台会先把数据源清掉。</p>
     */
    private BlockPos source;

    /** 上一次判出来的等级（-1 = 还没判过）；只在变化时下发，避免每 10 刻刷包 */
    private int alertLevel = -1;

    /** 轮询节流：读 DFC 要走一堆反射，别每刻都来 */
    private static final int SCAN_TICKS = 10;
    private int scanTimer;

    public AlarmConfig config() {
        return this.cfg;
    }

    /** 绑定的数据源坐标（可能已被拆掉）；{@code null} = 未联动 */
    public BlockPos sourcePos() {
        return this.source;
    }

    public boolean isAuto() {
        return this.source != null;
    }

    /** 最近一次判定的等级（0~3），{@code -1} = 还没判过 */
    public int alertLevel() {
        return this.alertLevel;
    }

    /**
     * 绑定一块显示屏当数据源，并**立刻**判一次（不用等 10 刻，绑定瞬间就该看到颜色）。
     */
    public void bindSource(BlockPos src) {
        this.source = src == null ? null : src.toImmutable();
        this.alertLevel = -1;
        this.scanTimer = 0;
        this.markDirty();
        if (this.world != null && !this.world.isRemote) {
            this.poll();
        }
    }

    /** 解除联动（重新绑控制台时调），灯保持当前颜色不动 */
    public void clearSource() {
        this.source = null;
        this.alertLevel = -1;
        this.markDirty();
    }

    /**
     * 写入一份新配置（控制台触发 / 染料刷色都走这里）。
     *
     * <p>顺带把 {@code ON} 写进方块状态：{@code getLightValue} 是按状态返回的，
     * 光改方块实体，原版光照不会跟着变（而且客户端也拿不到）。</p>
     */
    public void applyConfig(AlarmConfig next) {
        if (next == null) {
            return;
        }
        boolean changed = !next.sameAs(this.cfg);
        this.cfg.color = next.color;
        this.cfg.rpm = AlarmConfig.clampRpm(next.rpm);
        this.cfg.on = next.on;
        if (this.world != null && !this.world.isRemote) {
            IBlockState state = this.world.getBlockState(this.pos);
            if (state.getBlock() instanceof BlockAlarmLight
                    && state.getValue(BlockAlarmLight.ON).booleanValue() != this.cfg.on) {
                this.world.setBlockState(this.pos,
                        state.withProperty(BlockAlarmLight.ON, Boolean.valueOf(this.cfg.on)), 3);
            }
            if (changed) {
                this.markDirty();
                this.broadcastState();
            }
        }
    }

    /**
     * 控制台推送专用入口：**联动中的灯不理会**。
     *
     * <p>控制台每次红石沿都会把统一配置推给成员，如果它也能改联动灯，
     * 那告警颜色刚亮起来就被刷回红色常亮 —— 联动等于没做。
     * 想改回手动控制，用链接器重新把这盏灯绑到控制台即可（那会清掉数据源）。</p>
     */
    public void applyConsoleConfig(AlarmConfig next) {
        if (this.isAuto()) {
            return;
        }
        this.applyConfig(next);
    }

    // ==================== 告警轮询 ====================

    /**
     * 每 {@link #SCAN_TICKS} 刻读一次数据源，等级变了才改颜色。
     *
     * <p>为什么是"变了才改"：{@link #applyConfig} 会 {@code setBlockState} +
     * {@link #broadcastState()}（给 96 格内每个玩家发 TE 包）。每刻无脑写就是
     * v4.4 灯圈那种卡顿的来源，而告警状态几分钟也不一定变一次。</p>
     */
    @Override
    public void update() {
        if (this.world == null || this.world.isRemote || this.source == null) {
            return;
        }
        if (--this.scanTimer > 0) {
            return;
        }
        this.scanTimer = SCAN_TICKS;
        this.poll();
    }

    private void poll() {
        // 区块没加载 ≠ 数据源没了：这时候什么都不做，否则走远一点灯就会自己灭
        if (!this.world.isBlockLoaded(this.source)) {
            return;
        }
        TileEntity te = this.world.getTileEntity(this.source);
        AlertSignal sig;
        if (te instanceof TileDfcScreen) {
            TileDfcScreen screen = (TileDfcScreen) te;
            // 屏幕关着（没通红石）= 没在监视，别拿旧数据一直报警
            sig = screen.isPowered()
                    ? AlertRules.evaluate(screen.getData())
                    : new AlertSignal(AlertRules.OK, "off", "");
        } else if (te == null) {
            // 已经加载了却找不到方块实体 ⇒ 屏被拆了。按"正常"处理并留着绑定，
            // 重建之后再接上就行（玩家拆屏不一定想重新绑一次）
            sig = new AlertSignal(AlertRules.OK, "nosrc", "");
        } else {
            sig = new AlertSignal(AlertRules.OK, "nosrc", "");
        }
        if (sig.level == this.alertLevel) {
            return;
        }
        this.alertLevel = sig.level;
        AlarmConfig next = this.cfg.copy()
                .withColor(AlertRules.colorFor(sig.level))
                .withRpm(AlertRules.rpmFor(sig.level))
                .withOn(AlertRules.litFor(sig.level));
        this.applyConfig(next);
        dbgAlert(sig);
    }

    /** 只在等级变化时打一行（前 60 条），进游戏验联动时靠它判断"判没判到" */
    private static int dbgAlertCount;

    private void dbgAlert(AlertSignal sig) {
        if (dbgAlertCount++ < 60) {
            com.jiezhimod.JiezhiMod.warn("[Alarm] " + this.pos + " level=" + sig.level
                    + " " + sig.key + (sig.detail.isEmpty() ? "" : " (" + sig.detail + ")"));
        }
    }

    /**
     * 把颜色 / 转速推给周围玩家。
     *
     * <p>★★ 不能只靠 {@code notifyBlockUpdate}：它只发 {@code SPacketBlockChange}（方块状态），
     * <b>不带方块实体 NBT</b>；而 {@code SPacketUpdateTileEntity} 只在区块加载时随
     * {@code SPacketChunkData} 发一次。不自己发的话，服务端颜色改了、客户端画面一点不变
     * （v4.4 灯圈"永不变色"就是这么来的，见 docs/踩坑记录 #15）。</p>
     *
     * <p>这里直接发原版 TE 包，不用自建网络包：{@link #getUpdatePacket()} 已经把
     * 配置写进 NBT，客户端 {@link #onDataPacket} 读同一份 NBT。</p>
     */
    public void broadcastState() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        SPacketUpdateTileEntity pkt = this.getUpdatePacket();
        if (pkt == null) {
            return;
        }
        double range = 96.0D * 96.0D;
        for (Object o : this.world.playerEntities) {
            if (o instanceof net.minecraft.entity.player.EntityPlayerMP) {
                net.minecraft.entity.player.EntityPlayerMP p = (net.minecraft.entity.player.EntityPlayerMP) o;
                if (p.getDistanceSq(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D,
                        this.pos.getZ() + 0.5D) <= range) {
                    p.connection.sendPacket(pkt);
                }
            }
        }
    }

    /** 关灯（控制台下降沿 / 拆除时的兜底） */
    public void turnOff() {
        this.applyConfig(this.cfg.withOn(false));
    }

    // ==================== 渲染用 ====================

    /**
     * 此刻灯芯转到第几度（0~360）。
     *
     * @param partialTicks 帧插值，避免 20Hz 的世界刻看起来一顿一顿
     */
    public float angleDeg(float partialTicks) {
        double degPerTick = this.cfg.degreesPerSecond() / 20.0D;
        if (degPerTick <= 0.0D) {
            return this.phaseOffset;
        }
        long t = this.world == null ? 0L : this.world.getTotalWorldTime();
        double deg = (t + partialTicks) * degPerTick + this.phaseOffset;
        return (float) MathHelper.positiveModulo(deg, 360.0D);
    }

    /** 坐标哈希 → 0~360 的固定相位（重进世界不变、相邻灯自然错开） */
    private float phaseFromPos() {
        if (this.pos == null) {
            return 0.0F;
        }
        int h = this.pos.getX() * 73856093 ^ this.pos.getY() * 19349663 ^ this.pos.getZ() * 83492791;
        return Math.abs(h % 360);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        this.phaseOffset = this.phaseFromPos();
    }

    @Override
    public void setPos(BlockPos posIn) {
        super.setPos(posIn);
        this.phaseOffset = this.phaseFromPos();
    }

    // ==================== NBT / 同步 ====================

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.cfg.readFromNBT(compound);
        this.phaseOffset = this.phaseFromPos();
        this.source = compound.hasKey("AlarmSrcX")
                ? new BlockPos(compound.getInteger("AlarmSrcX"),
                        compound.getInteger("AlarmSrcY"), compound.getInteger("AlarmSrcZ"))
                : null;
        // 等级不存盘：重载后 10 刻内会重新判一次，存了反而可能和现场不符
        this.alertLevel = -1;
        this.scanTimer = 0;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        this.cfg.writeToNBT(compound);
        if (this.source != null) {
            compound.setInteger("AlarmSrcX", this.source.getX());
            compound.setInteger("AlarmSrcY", this.source.getY());
            compound.setInteger("AlarmSrcZ", this.source.getZ());
        }
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound c = super.getUpdateTag();
        this.cfg.writeToNBT(c);
        return c;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 1, this.getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        this.cfg.readFromNBT(pkt.getNbtCompound());
        this.phaseOffset = this.phaseFromPos();
        if (this.world != null) {
            // 方块状态里的 ON 也要跟上，否则原版光照还是旧的那一档
            IBlockState state = this.world.getBlockState(this.pos);
            if (state.getBlock() instanceof BlockAlarmLight
                    && state.getValue(BlockAlarmLight.ON).booleanValue() != this.cfg.on) {
                this.world.setBlockState(this.pos,
                        state.withProperty(BlockAlarmLight.ON, Boolean.valueOf(this.cfg.on)), 2);
            }
        }
    }

    /** 渲染包围盒要放大：光锥扫出去好几十格，按 1 格算会被整块裁掉 */
    @Override
    public double getMaxRenderDistanceSquared() {
        return 128.0D * 128.0D;
    }

    @Override
    public net.minecraft.util.math.AxisAlignedBB getRenderBoundingBox() {
        // v4.7：光柱 8 格长、远端半径 3.5，几何最远伸到约 8.7 格，
        // 按 8 格框会在屏幕边缘把光柱尾部整段裁掉 —— 放大到 12
        return new net.minecraft.util.math.AxisAlignedBB(this.pos).grow(12.0D);
    }
}
