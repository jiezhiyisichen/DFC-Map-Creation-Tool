package com.jiezhimod.network.msg;

import com.jiezhimod.block.TileDfcScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：调试界面里改一个方块的可调项。
 *
 * 目前只有一项 —— 显示器的**屏幕刷新率**。
 *
 * 【为什么只传"毫秒"而不传索引】服务端要拿它直接写进方块实体并广播，
 * 用真实数值比用下拉框下标更不容易错位（改选项顺序时不会静默改错值）。
 * 服务端仍会按白名单校验，非法值一律忽略。
 */
public class MessageDebugConfig implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int refreshMs;

    public MessageDebugConfig() {
    }

    public MessageDebugConfig(BlockPos pos, int refreshMs) {
        this.pos = pos;
        this.refreshMs = refreshMs;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.refreshMs = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeInt(this.refreshMs);
    }

    public BlockPos pos() {
        return pos;
    }

    public int refreshMs() {
        return refreshMs;
    }

    public static class Handler implements IMessageHandler<MessageDebugConfig, IMessage> {
        @Override
        public IMessage onMessage(MessageDebugConfig msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                // 8 格以内才允许调（和箱子同款判定）
                if (player.getDistanceSq(msg.pos.getX() + 0.5D, msg.pos.getY() + 0.5D,
                        msg.pos.getZ() + 0.5D) > 64.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(msg.pos);
                if (!(te instanceof TileDfcScreen)) {
                    return;
                }
                TileDfcScreen screen = (TileDfcScreen) te;
                if (!screen.setRefreshMs(msg.refreshMs)) {
                    return;
                }
                // 改完立刻读一次并广播：让玩家马上看到"这次改动生效了"，
                // 也顺便刷新客户端的刷新率镜像
                screen.refresh();
            });
            return null;
        }
    }
}
