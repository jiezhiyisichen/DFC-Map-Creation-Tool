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
        int before = this.delay;
        this.delay = next;
        this.timer.clampDelay(next);
        this.markDirty();
        // 立刻按新延迟重算一次：改小延迟时玩家期待"马上就有反应"
        this.recompute(false);
        if (this.world != null && !this.world.isRemote) {
            IBlockState state = this.world.getBlockState(this.pos);
            // flag 3：既让客户端收到方块状态，也把方块实体（延迟）同步过去
            this.world.notifyBlockUpdate(this.pos, state, state, 3);
            dbg("setDelay " + before + " -> " + this.delay + " @ " + this.pos);
        }
        return this.delay;
    }

    // ==================== 计时 ====================

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        this.recompute(false);
    }

    /** 放置后立刻算一次：背面本来就有信号时不用等玩家再来碰一下才亮 */
    public void updateNow() {
        this.recompute(false);
    }

    /**
     * 推进一刻并同步方块状态。
     *
     * 【为什么"变了才写状态"】每刻无条件 {@code setBlockState} 会引发全服范围的
     * 邻居更新风暴（一个中继器就能把整条线拖慢），所以只有亮灭/锁定真的翻转时才写。
     */
    /**
     * @param fromSchedule true = 由**计划刻**触发（见 BlockAdvancedRepeater#neighborChanged）：
     *                     这时如果计数器还没跟上，就直接按当前输入对齐。
     *                     正常情况（计数器每刻在跑）走到这里状态已经一致，等于什么都不做。
     */
    public void recompute(boolean fromSchedule) {
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
        boolean inputNow = state.getValue(BlockAdvancedRepeater.INPUT);

        // 内部状态与方块状态对不上（刚读档 / 刚放下 / 被别的东西改过）→ 先对齐，别误判成"输入变了"
        if (this.timer.output() != poweredNow) {
            this.timer.syncOutput(poweredNow);
        }

        // 【输入端那个火把】不管锁没锁都要显示真实输入电平（它只是指示灯）
        boolean input = BlockAdvancedRepeater.hasInput(world, pos, state);

        boolean powered = poweredNow;
        if (!locked) {
            this.timer.tick(input, this.delay);
            // 【计划刻只是兜底，不许抄近路】v4.4 实机"设好的延时用一次就没了"的根因：
            // 原来这里是 `if (fromSchedule) syncOutput(input)` —— 计划刻是**每一个邻居变化**
            // 都排一次的，它经常在延时还没走完时到点，于是直接跳过延时把输出顶上去。
            // 现在只有"确实已经等满（甚至超时）"才允许强制对齐。
            if (fromSchedule && input != this.timer.output()
                    && this.timer.elapsed() >= this.delay - 1) {
                this.timer.syncOutput(input);
            }
            powered = this.timer.output();
        }

        // 三个显示位各自独立：输出亮不亮、锁定条、输入火把
        if (powered == poweredNow && locked == state.getValue(BlockAdvancedRepeater.LOCKED)
                && input == inputNow) {
            return;
        }

        world.setBlockState(pos, state
                .withProperty(BlockAdvancedRepeater.POWERED, Boolean.valueOf(powered))
                .withProperty(BlockAdvancedRepeater.LOCKED, Boolean.valueOf(locked))
                .withProperty(BlockAdvancedRepeater.INPUT, Boolean.valueOf(input)), 2);
        // 只有**输出电平**变了才需要惊动邻居（红石粉重算连接 / 活塞 / 灯）。
        // 单纯"输入火把亮起来"是纯视觉，通知邻居纯属浪费。
        if (powered != poweredNow) {
            world.notifyNeighborsOfStateChange(pos, state.getBlock(), false);
        }
    }

    // ==================== NBT / 同步 ====================

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        // 【别用 getInteger 兜底】缺键时 getInteger 返回 0，clamp(0) = 1 tick ——
        // 于是"某个只带部分字段的 NBT 落到这里"就把玩家设的延时悄悄改成 0.05 秒
        // （实机反馈"设好的延时记不住"）。缺键就该保持默认，不要用 0 顶替。
        int before = this.delay;
        if (compound.hasKey(TAG_DELAY)) {
            this.delay = RepeaterDelay.clamp(compound.getInteger(TAG_DELAY));
        }
        this.timer.clampDelay(this.delay);
        if (before != this.delay) {
            dbg("nbt load delay " + before + " -> " + this.delay
                    + (compound.hasKey(TAG_DELAY) ? "" : " (no Delay key, kept)"));
        }
    }

    /** 排错日志：前 40 条全打，之后闭嘴（用户点名要"日志调试"，但不能刷屏） */
    private static int dbgCount;

    private static void dbg(String msg) {
        if (dbgCount++ < 40) {
            com.jiezhimod.JiezhiMod.warn("[Repeater] " + msg);
        }
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
