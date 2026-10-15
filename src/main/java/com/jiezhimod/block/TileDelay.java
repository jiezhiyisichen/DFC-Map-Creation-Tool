package com.jiezhimod.block;

import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageDelaySync;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;

/**
 * 红石延时器的方块实体：服务端权威，每 tick 采样输入、按延时翻转输出。
 *
 * 语义与中继器（repeater）一致，刻意做成**单向、锁存方向**：
 *   · 方向只锁存一次（inputDir），此后**只从这一个面读输入**；
 *   · 输出面由 inputDir 推导（直通 / 左 / 右 / 三向），永远不含 inputDir 本身；
 *   · 上升沿 / 下降沿都入队，整段波形整体后移 delayTicks，短脉冲不会被抹平。
 *
 * 【为什么必须锁存方向、不能每 tick 扫六个面】
 * 早期版本每 tick 采样全部六面取最大功率，并且只在"自己正在输出"时才跳过输出面。
 * 输出熄灭的那一刻 guard 失效，而此时自己的输出还经红石线绕在别的面上，
 * 于是那个面被当成新输入 -> 再次排期 -> 再次输出，形成自激振荡（表现为"整出循环了"）。
 * 锁存单面后，输入面恒不等于任何输出面，结构上不可能自激。
 *
 * 客户端实例只拿来给 GUI 显示（配置与状态由 MessageDelaySync 推送），不参与运算。
 */
public class TileDelay extends TileEntity implements ITickable {

    private static final String KEY_VALUE = "value";
    private static final String KEY_UNIT = "unit";
    private static final String KEY_MODE = "mode";
    private static final String KEY_OUTPUT = "out";
    private static final String KEY_INPUT = "in";
    private static final String KEY_DIR = "dir";

    /** 状态同步的最小间隔（tick），避免高频时钟刷爆包 */
    private static final long SYNC_INTERVAL = 4L;

    private int value = 20;
    private int unit = DelayLogic.UNIT_TICK;
    private int mode = DelayLogic.MODE_FRONT;
    private int delayTicks = DelayLogic.toTicks(20, DelayLogic.UNIT_TICK);

    private boolean inputOn = false;
    private boolean outputOn = false;
    /** 信号来源方向（从本方块指向来源邻居）；还没收到过输入时为 null */
    private EnumFacing inputDir = null;
    /** 客户端专用：服务端推来的下一次翻转时间，用来算剩余 tick */
    private long syncedNextFire = Long.MAX_VALUE;

    private final DelayQueue queue = new DelayQueue();
    private boolean firstTick = true;
    private boolean syncDirty = false;
    private long lastSyncTick = Long.MIN_VALUE;

    // ==================== 配置 ====================

    public int getValue() {
        return value;
    }

    public int getUnit() {
        return unit;
    }

    public int getMode() {
        return mode;
    }

    public int delayTicks() {
        return delayTicks;
    }

    public boolean isInputOn() {
        return inputOn;
    }

    public boolean isOutputOn() {
        return outputOn;
    }

    /** 信号来源方向；null = 还没收到过输入 */
    public EnumFacing getInputDir() {
        return inputDir;
    }

    /** 给 GUI 用：当前模式下真正会输出的方向 */
    public java.util.List<EnumFacing> outputDirs() {
        return DelayLogic.outputSides(this.inputDir, this.mode);
    }

    /** 给 GUI 用：距离下一次翻转还有多少 tick（没排队返回 0） */
    public int remainingTicks(long now) {
        long fire = syncedNextFire;
        if (fire == Long.MAX_VALUE) {
            return 0;
        }
        long left = fire - now;
        return left <= 0 ? 0 : (int) Math.min(left, Integer.MAX_VALUE);
    }

    /** 服务端落库：clamp 一遍再存，然后广播给周围玩家 */
    public void applyConfig(int newValue, int newUnit, int newMode) {
        int v = DelayLogic.clampValue(newValue);
        int u = DelayLogic.clampUnit(newUnit);
        int m = DelayLogic.clampMode(newMode);
        boolean changed = v != this.value || u != this.unit || m != this.mode;
        this.value = v;
        this.unit = u;
        this.mode = m;
        this.delayTicks = DelayLogic.toTicks(v, u);
        if (changed) {
            this.markDirty();
        }
        if (this.world != null && !this.world.isRemote) {
            this.broadcastSync();
        }
    }

    /** 客户端收到同步包：只更新显示用字段，不触发任何世界改动 */
    public void applySync(int newValue, int newUnit, int newMode, boolean newInputOn,
                          boolean newOutputOn, int inputDirOrdinal, long nextFire) {
        this.value = DelayLogic.clampValue(newValue);
        this.unit = DelayLogic.clampUnit(newUnit);
        this.mode = DelayLogic.clampMode(newMode);
        this.delayTicks = DelayLogic.toTicks(this.value, this.unit);
        this.inputOn = newInputOn;
        this.outputOn = newOutputOn;
        this.inputDir = DelayLogic.facingByOrdinal(inputDirOrdinal);
        this.syncedNextFire = nextFire;
    }

    /** 右键开 GUI 前，服务端先把当前配置推给这个玩家，避免界面读到默认值 */
    public void syncTo(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        ModNetwork.CHANNEL.sendTo(snapshot(), (EntityPlayerMP) player);
    }

    private MessageDelaySync snapshot() {
        return new MessageDelaySync(this.pos, this.value, this.unit, this.mode,
                this.inputOn, this.outputOn, ordinalOf(this.inputDir), this.queue.nextFireTime());
    }

    private static int ordinalOf(EnumFacing facing) {
        return facing == null ? -1 : facing.getIndex();
    }

    private void broadcastSync() {
        if (this.world == null) {
            return;
        }
        ModNetwork.CHANNEL.sendToAllAround(snapshot(),
                new NetworkRegistry.TargetPoint(this.world.provider.getDimension(),
                        this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D, 64.0D));
    }

    private void syncIfNeeded(long now) {
        if (!this.syncDirty) {
            return;
        }
        if (this.lastSyncTick != Long.MIN_VALUE && now - this.lastSyncTick < SYNC_INTERVAL) {
            return;
        }
        this.syncDirty = false;
        this.lastSyncTick = now;
        this.broadcastSync();
    }

    // ==================== 每 tick ====================

    @Override
    public void update() {
        World w = this.world;
        if (w == null || w.isRemote) {
            return;
        }
        IBlockState state = w.getBlockState(this.pos);
        if (!(state.getBlock() instanceof BlockDelay)) {
            return;
        }
        long now = w.getTotalWorldTime();

        if (this.firstTick) {
            this.firstTick = false;
            // 读档回来：把掉电前的输出电平同步回方块状态（POWERED 只影响贴图）
            if (state.getValue(BlockDelay.POWERED) != this.outputOn) {
                w.setBlockState(this.pos, state.withProperty(BlockDelay.POWERED, this.outputOn), 2);
            }
            w.notifyNeighborsOfStateChange(this.pos, state.getBlock(), true);
        }

        if (this.queue.applyDue(now)) {
            this.setOutput(this.queue.state());
        }

        // ---- 输入采样：中继器语义，只认"锁存的那一面" ----
        // 关键：绝不扫描六个面。中继器只从**背面**读输入，输出在正面，
        // 两者由同一个锁存方向 inputDir 决定，物理上不可能自己喂自己。
        // 之前每 tick 扫六面、且只在 selfDriving() 时才跳过输出面，
        // 输出熄灭后 guard 失效 -> 自己的输出经红石线绕回另一个面 -> 自激振荡。
        if (this.inputDir == null) {
            // 尚未锁定方向：只在完全空闲（不输出、队列空）时探测一次，
            // 避免在自己正输出时把输出面误判成输入面。
            if (!this.outputOn && this.queue.isEmpty()) {
                this.inputDir = detectInputSide();
                if (this.inputDir != null) {
                    this.syncDirty = true;
                }
            }
        }

        if (this.inputDir != null) {
            boolean in = this.powerAt(this.inputDir) > 0;
            if (in != this.inputOn) {
                this.inputOn = in;
                this.queue.schedule(now + this.delayTicks, in);
                this.syncDirty = true;
            }
        }

        this.syncIfNeeded(now);
    }

    /**
     * 首次锁定方向：取红石功率最大的那个面。
     *
     * 只在 {@link #inputDir} 为 null 且方块完全空闲时调用一次，
     * 锁定后方向就固定下来（玩家可用 GUI 的"重检方向"重新锁定）。
     */
    private EnumFacing detectInputSide() {
        int[] power = new int[EnumFacing.values().length];
        int best = 0;
        for (EnumFacing f : EnumFacing.values()) {
            power[f.getIndex()] = powerAt(f);
            if (power[f.getIndex()] > best) {
                best = power[f.getIndex()];
            }
        }
        return DelayLogic.pickInputSide(power);
    }

    /** 玩家在 GUI 里点"重检方向"：丢掉锁存方向，下一个空闲 tick 重新探测 */
    public void resetInputDir() {
        if (this.inputDir == null) {
            return;
        }
        this.inputDir = null;
        this.inputOn = false;
        this.queue.clear();
        this.setOutput(false);
        this.syncDirty = true;
        this.broadcastSync();
    }

    /** 取某个方向上邻居给本方块的红石功率 */
    private int powerAt(EnumFacing f) {
        BlockPos neighbor = this.pos.offset(f);
        int power = this.world.getRedstonePower(neighbor, f);
        IBlockState nb = this.world.getBlockState(neighbor);
        // 红石线的弱功率只在特定方向给出，这里照原版中继器的做法补一次
        if (nb.getBlock() == Blocks.REDSTONE_WIRE) {
            power = Math.max(power, ((Integer) nb.getValue(BlockRedstoneWire.POWER)).intValue());
        }
        return power;
    }

    private void setOutput(boolean on) {
        if (on == this.outputOn) {
            return;
        }
        this.outputOn = on;
        this.markDirty();
        World w = this.world;
        IBlockState state = w.getBlockState(this.pos);
        if (state.getBlock() instanceof BlockDelay) {
            w.setBlockState(this.pos, state.withProperty(BlockDelay.POWERED, on), 2);
        }
        Block self = state.getBlock();
        w.notifyNeighborsOfStateChange(this.pos, self, true);
        for (EnumFacing out : DelayLogic.outputSides(this.inputDir, this.mode)) {
            // 让信号能再穿一格（和中继器一样：通知输出面邻居的邻居）
            w.notifyNeighborsOfStateExcept(this.pos.offset(out), self, out.getOpposite());
        }
        this.syncDirty = true;
        this.broadcastSync();
    }

    // ==================== 存档 ====================

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        return saveConfig(compound);
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        loadConfig(compound);
    }

    /**
     * 只写本模组的配置字段（不碰 TileEntity 的 id/x/y/z）。
     * 单独抽出来是因为 super.writeToNBT 会走 TileEntity.writeInternal，
     * 那里要求类已在注册表里有映射——离线自测环境没有注册表，调不通。
     */
    public NBTTagCompound saveConfig(NBTTagCompound compound) {
        compound.setInteger(KEY_VALUE, this.value);
        compound.setInteger(KEY_UNIT, this.unit);
        compound.setInteger(KEY_MODE, this.mode);
        compound.setBoolean(KEY_OUTPUT, this.outputOn);
        compound.setBoolean(KEY_INPUT, this.inputOn);
        compound.setInteger(KEY_DIR, ordinalOf(this.inputDir));
        return compound;
    }

    /** 与 saveConfig 对称；读取时一律 clamp，防止改档塞进非法值 */
    public void loadConfig(NBTTagCompound compound) {
        this.value = DelayLogic.clampValue(compound.getInteger(KEY_VALUE));
        this.unit = DelayLogic.clampUnit(compound.getInteger(KEY_UNIT));
        this.mode = DelayLogic.clampMode(compound.getInteger(KEY_MODE));
        this.delayTicks = DelayLogic.toTicks(this.value, this.unit);
        this.outputOn = compound.getBoolean(KEY_OUTPUT);
        this.inputOn = compound.getBoolean(KEY_INPUT);
        this.inputDir = DelayLogic.facingByOrdinal(compound.getInteger(KEY_DIR));
        this.queue.reset(this.outputOn);
    }

    // ==================== 便于自测 ====================

    public DelayQueue queue() {
        return queue;
    }
}
