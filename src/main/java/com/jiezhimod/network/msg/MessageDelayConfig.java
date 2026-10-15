package com.jiezhimod.network.msg;

import com.jiezhimod.block.TileDelay;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：写入红石延时器的配置（数值 / 单位 / 输出方向）。
 *
 * 服务端是权威：收到后 clamp 一遍再落库，并回推同步包给周围玩家。
 */
public class MessageDelayConfig implements IMessage {

    private BlockPos pos;
    private int value;
    private int unit;
    private int mode;
    /** true = 顺便让服务端丢掉锁存的输入方向，下个空闲 tick 重新探测 */
    private boolean redetect;

    public MessageDelayConfig() {
        this.pos = BlockPos.ORIGIN;
    }

    public MessageDelayConfig(BlockPos pos, int value, int unit, int mode) {
        this(pos, value, unit, mode, false);
    }

    public MessageDelayConfig(BlockPos pos, int value, int unit, int mode, boolean redetect) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.value = value;
        this.unit = unit;
        this.mode = mode;
        this.redetect = redetect;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.value = buf.readInt();
        this.unit = buf.readInt();
        this.mode = buf.readInt();
        this.redetect = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeInt(this.value);
        buf.writeInt(this.unit);
        buf.writeInt(this.mode);
        buf.writeBoolean(this.redetect);
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

    public boolean isRedetect() {
        return redetect;
    }

    public static class Handler implements IMessageHandler<MessageDelayConfig, IMessage> {
        @Override
        public IMessage onMessage(MessageDelayConfig message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final int value = message.value;
            final int unit = message.unit;
            final int mode = message.mode;
            final boolean redetect = message.redetect;
            player.getServerWorld().addScheduledTask(() -> {
                TileEntity te = player.world.getTileEntity(pos);
                if (te instanceof TileDelay) {
                    TileDelay tile = (TileDelay) te;
                    if (redetect) {
                        // 先解锁方向再写配置：applyConfig 只碰数值/单位/模式，
                        // 两者互不覆盖，顺序无关。
                        tile.resetInputDir();
                    }
                    tile.applyConfig(value, unit, mode);
                }
            });
            return null;
        }
    }
}
