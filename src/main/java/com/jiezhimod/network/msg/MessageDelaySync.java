package com.jiezhimod.network.msg;

import com.jiezhimod.block.TileDelay;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：把红石延时器的配置与实时状态推给客户端。
 *
 * 两个用途：
 *   1) 右键开 GUI 前先推一次，避免界面读到默认配置；
 *   2) 输出电平翻转 / 配置变化时广播给周围玩家，GUI 上的状态与剩余 tick 才是活的。
 */
public class MessageDelaySync implements IMessage {

    private BlockPos pos;
    private int value;
    private int unit;
    private int mode;
    private boolean inputOn;
    private boolean outputOn;
    /** 信号来源方向的序号（-1 = 还没有输入），客户端拿来画"输入来自 X -> 输出 Y" */
    private int inputDir;
    private long nextFireTime;

    public MessageDelaySync() {
        this.pos = BlockPos.ORIGIN;
        this.inputDir = -1;
    }

    public MessageDelaySync(BlockPos pos, int value, int unit, int mode, boolean inputOn,
                            boolean outputOn, int inputDir, long nextFireTime) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.value = value;
        this.unit = unit;
        this.mode = mode;
        this.inputOn = inputOn;
        this.outputOn = outputOn;
        this.inputDir = inputDir;
        this.nextFireTime = nextFireTime;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.value = buf.readInt();
        this.unit = buf.readInt();
        this.mode = buf.readInt();
        this.inputOn = buf.readBoolean();
        this.outputOn = buf.readBoolean();
        this.inputDir = buf.readInt();
        this.nextFireTime = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeInt(this.value);
        buf.writeInt(this.unit);
        buf.writeInt(this.mode);
        buf.writeBoolean(this.inputOn);
        buf.writeBoolean(this.outputOn);
        buf.writeInt(this.inputDir);
        buf.writeLong(this.nextFireTime);
    }

    public BlockPos getPos() {
        return pos;
    }

    public int getValue() {
        return value;
    }

    public int getUnit() {
        return unit;
    }

    public int getMode() {
        return mode;
    }

    public boolean isInputOn() {
        return inputOn;
    }

    public boolean isOutputOn() {
        return outputOn;
    }

    public int getInputDir() {
        return inputDir;
    }

    public long getNextFireTime() {
        return nextFireTime;
    }

    public static class Handler implements IMessageHandler<MessageDelaySync, IMessage> {
        @Override
        public IMessage onMessage(MessageDelaySync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                TileEntity te = Minecraft.getMinecraft().world == null
                        ? null : Minecraft.getMinecraft().world.getTileEntity(message.pos);
                if (te instanceof TileDelay) {
                    ((TileDelay) te).applySync(message.value, message.unit, message.mode,
                            message.inputOn, message.outputOn, message.inputDir, message.nextFireTime);
                }
            });
            return null;
        }
    }
}
