package com.jiezhimod.network.msg;

import com.jiezhimod.block.TileLabelScreen;
import com.jiezhimod.dfc.LabelLayout;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：改**标注显示器**某一格绑定的指标。
 *
 * 【为什么只发"第几格 + 新值"】七格配置本身不长，但玩家一次只点一下箭头，
 * 发整张表既没必要也让"并发改两格"变得难以推理。一格一个包，服务端收到就写那一格。
 *
 * 【永远不要相信客户端】下标和指标编号都在服务端重新夹一遍：
 * 越界的下标直接丢弃，未知指标降级成"空槽"（{@link LabelLayout#clampMetric}）。
 */
public class MessageLabelConfig implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    /** 第几格（0 ~ {@link LabelLayout#SLOTS}-1） */
    private int slot;
    /** 新的指标编号 */
    private int metric;

    public MessageLabelConfig() {
    }

    public MessageLabelConfig(BlockPos pos, int slot, int metric) {
        this.pos = pos;
        this.slot = slot;
        this.metric = metric;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.slot = buf.readByte();
        this.metric = buf.readByte() & 255;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeByte(this.slot);
        buf.writeByte(this.metric);
    }

    public static class Handler implements IMessageHandler<MessageLabelConfig, IMessage> {
        @Override
        public IMessage onMessage(MessageLabelConfig msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                // 8 格以内才允许调（与刷新率、密码盘配置同款判定）
                if (player.getDistanceSq(msg.pos.getX() + 0.5D, msg.pos.getY() + 0.5D,
                        msg.pos.getZ() + 0.5D) > 64.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(msg.pos);
                if (!(te instanceof TileLabelScreen)) {
                    return;
                }
                ((TileLabelScreen) te).setMetric(msg.slot, msg.metric);
            });
            return null;
        }
    }
}
