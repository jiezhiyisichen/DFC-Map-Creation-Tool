package com.jiezhimod.network.msg;

import com.jiezhimod.block.TilePasswordConsole;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：改密码控制台的两项设置。
 *
 * 只传"要设成什么"，由服务端校验后再落到方块实体上 ——
 * 与自定义屏那套一致：**客户端不直接改数据**，改完由服务端广播回来。
 * 另外做了距离校验，避免改包的客户端隔着几百格去改别人的控制台。
 */
public class MessageConsoleConfig implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int action;
    private boolean output;

    public MessageConsoleConfig() {
    }

    public MessageConsoleConfig(BlockPos pos, int action, boolean output) {
        this.pos = pos;
        this.action = action;
        this.output = output;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.action = buf.readInt();
        this.output = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeInt(this.action);
        buf.writeBoolean(this.output);
    }

    public BlockPos pos() {
        return pos;
    }

    public int action() {
        return action;
    }

    public boolean output() {
        return output;
    }

    public static class Handler implements IMessageHandler<MessageConsoleConfig, IMessage> {
        @Override
        public IMessage onMessage(MessageConsoleConfig msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                // 8 格以内才允许改（原版箱子同款判定）
                if (player.getDistanceSq(msg.pos.getX() + 0.5D, msg.pos.getY() + 0.5D,
                        msg.pos.getZ() + 0.5D) > 64.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(msg.pos);
                if (!(te instanceof TilePasswordConsole)) {
                    return;
                }
                TilePasswordConsole console = (TilePasswordConsole) te;
                console.setRedstoneAction(msg.action);
                console.setOutputWhenCorrect(msg.output);
            });
            return null;
        }
    }
}
