package com.jiezhimod.network.msg;

import com.jiezhimod.block.TileAdvancedRepeater;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：设置高级红石中继器的延迟（单位 tick）。
 *
 * 【为什么永远传 tick】单位换算（秒 ↔ tick）只在界面里做，网络层只认内部量纲，
 * 少一层换算就少一处"两边算得不一样"。服务端再钳一次范围 —— 客户端不可信。
 */
public class MessageRepeaterDelay implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int ticks;

    public MessageRepeaterDelay() {
    }

    public MessageRepeaterDelay(BlockPos pos, int ticks) {
        this.pos = pos;
        this.ticks = ticks;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.ticks = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeInt(this.ticks);
    }

    public BlockPos pos() {
        return this.pos;
    }

    public int ticks() {
        return this.ticks;
    }

    public static class Handler implements IMessageHandler<MessageRepeaterDelay, IMessage> {
        @Override
        public IMessage onMessage(MessageRepeaterDelay msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                // 8 格以内（和箱子同款判定），越远越可能是在乱发包
                if (player.getDistanceSq(msg.pos.getX() + 0.5D, msg.pos.getY() + 0.5D,
                        msg.pos.getZ() + 0.5D) > 64.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(msg.pos);
                if (!(te instanceof TileAdvancedRepeater)) {
                    return;
                }
                // setDelay 内部会钳范围、重算并同步给所有看得到的客户端
                ((TileAdvancedRepeater) te).setDelay(msg.ticks);
            });
            return null;
        }
    }
}
