package com.jiezhimod.block;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;

/**
 * 密码控制台：**控制台是接收红石信号、并且输出红石信号的那一端**；
 * 密码盘只负责"输入密码"。
 *
 * 两件事：
 *   1. **收到红石信号后干什么**（{@link #getRedstoneAction()}）：
 *      什么都不做 / 锁定密码盘 / 解锁密码盘 / 清空已输入 / 锁定并清空；
 *   2. **密码正确时输出红石信号**（{@link #isOutputWhenCorrect()}）：
 *      密码盘报"已通过"就持续输出 15 级弱信号，直到被清空/锁定/重置。
 *
 * 【为什么轮询而不是监听红石事件】1.12.2 里红石变化的通知路径很碎
 * （{@code neighborChanged} / {@code onNeighborChange} / 强充能 / 弱充能各走一套），
 * 漏一条就会"引信按下去没反应"。这里每 5 tick 读一次
 * {@code isBlockIndirectlyGettingPowered} 做**上升沿检测**，行为确定、也便宜。
 */
public class TilePasswordConsole extends TileEntity implements ITickable {

    /** 收到红石信号后：什么都不做 */
    public static final int ACT_NONE = 0;
    /** 锁定密码盘（拒绝一切输入） */
    public static final int ACT_LOCK = 1;
    /** 解锁密码盘 */
    public static final int ACT_UNLOCK = 2;
    /** 清空密码盘上已经输入的部分 */
    public static final int ACT_CLEAR = 3;
    /** 锁定 + 清空（默认，最符合"红石信号把键盘锁死"的直觉） */
    public static final int ACT_LOCK_CLEAR = 4;

    public static final int ACTION_COUNT = 5;

    /** 轮询间隔（tick） */
    private static final int SCAN_INTERVAL = 5;

    private BlockPos keypadPos;
    private int redstoneAction = ACT_LOCK_CLEAR;
    private boolean outputWhenCorrect = true;
    private boolean outputOn;

    private boolean lastPowered;
    private int scanTimer;
    /** 上一次红石触发实际做了什么（给 GUI 显示，也方便排查） */
    private int lastTriggeredAction = ACT_NONE;

    // ==================== 配置读写 ====================

    public BlockPos getKeypadPos() {
        return this.keypadPos;
    }

    public void setKeypadPos(BlockPos pos) {
        this.keypadPos = pos;
        markDirty();
        sync();
    }

    public void clearKeypad() {
        this.keypadPos = null;
        this.outputOn = false;
        markDirty();
        sync();
        notifyNeighbors();
    }

    public int getRedstoneAction() {
        return this.redstoneAction;
    }

    public void setRedstoneAction(int action) {
        if (action < 0 || action >= ACTION_COUNT) {
            return;
        }
        this.redstoneAction = action;
        markDirty();
        sync();
    }

    public boolean isOutputWhenCorrect() {
        return this.outputWhenCorrect;
    }

    public void setOutputWhenCorrect(boolean value) {
        this.outputWhenCorrect = value;
        markDirty();
        sync();
        // 关掉输出要立刻停止供能，否则停在"还在输出"的状态
        if (!value && this.outputOn) {
            this.outputOn = false;
            notifyNeighbors();
        }
    }

    public boolean isOutputOn() {
        return this.outputOn;
    }

    public int getLastTriggeredAction() {
        return this.lastTriggeredAction;
    }

    /** 取绑定的密码盘实体；没有/被拆/换块了都返回 null */
    public TileKeypad getKeypad() {
        if (this.world == null || this.keypadPos == null) {
            return null;
        }
        TileEntity te = this.world.getTileEntity(this.keypadPos);
        return te instanceof TileKeypad ? (TileKeypad) te : null;
    }

    // ==================== tick ====================

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        if (++this.scanTimer < SCAN_INTERVAL) {
            return;
        }
        this.scanTimer = 0;

        // ---- 1. 红石上升沿：执行配置的动作 ----
        boolean powered = this.world.isBlockIndirectlyGettingPowered(this.pos) > 0;
        if (powered && !this.lastPowered) {
            applyRedstoneAction();
        }
        this.lastPowered = powered;

        // ---- 2. 密码正确 -> 输出 ----
        TileKeypad keypad = getKeypad();
        boolean want = this.outputWhenCorrect && keypad != null && keypad.isAccepted();
        if (want != this.outputOn) {
            this.outputOn = want;
            notifyNeighbors();
            markDirty();
            sync();
        }
    }

    private void applyRedstoneAction() {
        TileKeypad keypad = getKeypad();
        this.lastTriggeredAction = this.redstoneAction;
        if (keypad == null || this.redstoneAction == ACT_NONE) {
            return;
        }
        switch (this.redstoneAction) {
            case ACT_LOCK:
                keypad.setLocked(true);
                break;
            case ACT_UNLOCK:
                keypad.setLocked(false);
                break;
            case ACT_CLEAR:
                keypad.clearBuffer();
                break;
            case ACT_LOCK_CLEAR:
                keypad.setLocked(true);
                keypad.clearBuffer();
                break;
            default:
                break;
        }
    }

    private void notifyNeighbors() {
        if (this.world != null) {
            this.world.notifyNeighborsOfStateChange(this.pos,
                    this.world.getBlockState(this.pos).getBlock(), true);
        }
    }

    // ==================== 同步 / 存盘 ====================

    public void sync() {
        if (this.world != null && !this.world.isRemote) {
            this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos),
                    this.world.getBlockState(this.pos), 3);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        super.writeToNBT(c);
        if (this.keypadPos != null) {
            c.setBoolean("HasKeypad", true);
            c.setInteger("KeypadX", this.keypadPos.getX());
            c.setInteger("KeypadY", this.keypadPos.getY());
            c.setInteger("KeypadZ", this.keypadPos.getZ());
        }
        c.setInteger("Action", this.redstoneAction);
        c.setBoolean("OutCorrect", this.outputWhenCorrect);
        c.setBoolean("OutOn", this.outputOn);
        c.setInteger("LastAction", this.lastTriggeredAction);
        return c;
    }

    @Override
    public void readFromNBT(NBTTagCompound c) {
        super.readFromNBT(c);
        this.keypadPos = c.getBoolean("HasKeypad")
                ? new BlockPos(c.getInteger("KeypadX"), c.getInteger("KeypadY"), c.getInteger("KeypadZ"))
                : null;
        this.redstoneAction = c.getInteger("Action");
        this.outputWhenCorrect = !c.hasKey("OutCorrect") || c.getBoolean("OutCorrect");
        this.outputOn = c.getBoolean("OutOn");
        this.lastTriggeredAction = c.getInteger("LastAction");
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }

    @Override
    public boolean shouldRefresh(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos,
                                 net.minecraft.block.state.IBlockState oldState,
                                 net.minecraft.block.state.IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}
