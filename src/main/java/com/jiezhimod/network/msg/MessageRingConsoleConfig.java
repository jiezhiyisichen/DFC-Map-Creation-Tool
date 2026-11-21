package com.jiezhimod.network.msg;

import com.jiezhimod.block.ContainerRingConsole;
import com.jiezhimod.block.TileRingConsole;
import com.jiezhimod.ring.RingConsoleConfig;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：灯圈控制台「应用」按钮（整份配置一起发）。
 *
 * <p>【为什么整份发而不是逐项发】控制台有十来个参数，逐项发要十几个包类型；
 * 整份发只有一个包，服务端收完整份再钳一遍数值（{@code RingConsoleConfig} 的
 * 读写里已经钳了），伪造包最多也就是"配置不合法的值被夹回合法区间"。</p>
 */
public class MessageRingConsoleConfig implements IMessage {

    private BlockPos pos;
    private final RingConsoleConfig config = new RingConsoleConfig();

    public MessageRingConsoleConfig() {
        this.pos = BlockPos.ORIGIN;
    }

    public MessageRingConsoleConfig(BlockPos pos, RingConsoleConfig config) {
        this.pos = pos;
        if (config != null) {
            this.config.actions = config.actions;
            this.config.color = config.color;
            this.config.colorFade = config.colorFade;
            this.config.colorFadeTicks = config.colorFadeTicks;
            this.config.onFade = config.onFade;
            this.config.onFadeTicks = config.onFadeTicks;
            this.config.offFade = config.offFade;
            this.config.offFadeTicks = config.offFadeTicks;
            this.config.blinkPeriod = config.blinkPeriod;
            this.config.blinkTotal = config.blinkTotal;
            this.config.blinkFadeIn = config.blinkFadeIn;
            this.config.blinkFadeOut = config.blinkFadeOut;
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.config.readFrom(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        this.config.writeTo(buf);
    }

    public static class Handler implements IMessageHandler<MessageRingConsoleConfig, IMessage> {
        @Override
        public IMessage onMessage(MessageRingConsoleConfig msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                TileEntity te = player.world.getTileEntity(msg.pos);
                if (!(te instanceof TileRingConsole)) {
                    return;
                }
                // 距离校验：别让改包的客户端隔着一百格去改别人基地里那台控制台
                if (player.getDistanceSq(msg.pos.getX() + 0.5D, msg.pos.getY() + 0.5D,
                        msg.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                    return;
                }
                // 只认玩家实际开着的那个容器，防止隔空改别台控制台
                if (!(player.openContainer instanceof ContainerRingConsole)
                        || ((ContainerRingConsole) player.openContainer).getTile() != te) {
                    return;
                }
                ((TileRingConsole) te).applyConfig(msg.config);
            });
            return null;
        }
    }
}
