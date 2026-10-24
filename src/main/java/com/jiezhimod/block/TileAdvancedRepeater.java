package com.jiezhimod.block;

import com.jiezhimod.repeater.RepeaterDelay;
import com.jiezhimod.repeater.RepeaterTimer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 高级红石中继器的方块实体：**延迟 + 计数器**。
 *
 * <p>延迟放这里而不是方块状态里，是因为方块状态要能画出模型 —— 延迟做成属性的话
 * blockstate 会从 16 个变体膨胀到 4800 个（4 朝向 x 1200 延迟 x 亮灭）。</p>
 *
 * <p>计时方案与"为什么不用计划刻"见 {@link RepeaterTimer}。这里只负责：
 * 每刻推进计数器 → 亮灭变化时改方块状态 + 通知邻居 → 顺手维护锁定状态。</p>
 *
 * <p>客户端也持有这个方块实体（{@link #getUpdatePacket()} 会把延迟同步过去），
 * 但**客户端不跑计时**：一切以服务端为准，避免两边各算一套导致画面和实际不同步。</p>
 */
public class TileAdvancedRepeater extends TileEntity implements ITickable {

    /** NBT 键名（同时也是物品 NBT 的键名，见 {@link BlockAdvancedRepeater#readDelay}） */
    public static final String TAG_DELAY = "Delay";

    /** 延迟，单位 tick */
    private int delay = RepeaterDelay.DEFAULT_TICKS;

    /** 计时状态机：输出 + 已等待刻数（见 RepeaterTimer 的说明） */
    private final RepeaterTimer timer = new RepeaterTimer();

    public TileAdvancedRepeater() {
    }

    public int getDelay() {
        return this.delay;
    }

    /** 延迟的秒数显示（客户端 GUI 直接用） */
    public String getDelaySecondsText() {
        return RepeaterDelay.secondsText(this.delay);
    }

    /**
     * 改延迟。会被钳到合法区间，并把计数器一起夹进新区间
     * （否则从 60 秒改成 1 tick 之后输出要等 60 秒才会动）。
     *
     * @return 实际生效的值（越界时是钳制后的值）
     */
    public int setDelay(int ticks) {
        int next = RepeaterDelay.clamp(ticks);
        if (next == this.delay) {
            return this.delay;
        }
        this.delay = next;
        this.timer.clampDelay(next);
        this.markDirty();
        // 立刻按新延迟重算一次：改小延迟时玩家期待"马上就有反应"
        this.recompute();
        if (this.world != null && !this.world.isRemote) {
            IBlockState state = this.world.getBlockState(this.pos);
            // flag 3：既让客户端收到方块状态，也把方块实体（延迟）同步过去
            this.world.notifyBlockUpdate(this.pos, state, state, 3);
        }
        return this.delay;
    }

    // ==================== 计时 ====================

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        this.recompute();
    }

    /** 放置后立刻算一次：背面本来就有信号时不用等玩家再来碰一下才亮 */
    public void updateNow() {
        this.recompute();
    }

    /**
     * 推进一刻并同步方块状态。
     *
     * 【为什么"变了才写状态"】每刻无条件 {@code setBlockState} 会引发全服范围的
     * 邻居更新风暴（一个中继器就能把整条线拖慢），所以只有亮灭/锁定真的翻转时才写。
     */
    private void recompute() {
        World world = this.world;
        if (world == null || world.isRemote) {
            return;
        }
        BlockPos pos = this.pos;
        if (pos == null) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockAdvancedRepeater)) {
            return;
        }

        boolean locked = BlockAdvancedRepeater.isLocked(world, pos, state);
        boolean poweredNow = state.getValue(BlockAdvancedRepeater.POWERED);

        // 内部状态与方块状态对不上（刚读档 / 刚放下 / 被别的东西改过）→ 先对齐，别误判成"输入变了"
        if (this.timer.output() != poweredNow) {
            this.timer.syncOutput(poweredNow);
        }

        boolean powered = poweredNow;
        if (!locked) {
            boolean input = BlockAdvancedRepeater.hasInput(world, pos, state);
            this.timer.tick(input, this.delay);
            powered = this.timer.output();
        }

        if (powered == poweredNow
                && state.getValue(BlockAdvancedRepeater.LOCKED) == locked) {
            return;
        }

        world.setBlockState(pos, state
                .withProperty(BlockAdvancedRepeater.POWERED, Boolean.valueOf(powered))
                .withProperty(BlockAdvancedRepeater.LOCKED, Boolean.valueOf(locked)), 2);
        // flag 2 只同步客户端、不通知邻居，所以这里补一次真正的邻居更新：
        // 红石粉要重新算连接、活塞要判断伸缩、灯要亮
        world.notifyNeighborsOfStateChange(pos, state.getBlock(), false);
    }

    // ==================== NBT / 同步 ====================

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.delay = RepeaterDelay.clamp(compound.getInteger(TAG_DELAY));
        // 计时器不存盘：区块重载后从 0 开始，输出状态以方块状态为准（下一 tick 会对齐）
        this.timer.clampDelay(this.delay);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger(TAG_DELAY, this.delay);
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound nbt = super.getUpdateTag();
        nbt.setInteger(TAG_DELAY, this.delay);
        return nbt;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 1, this.getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        this.readFromNBT(pkt.getNbtCompound());
    }
}
