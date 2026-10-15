package com.jiezhimod.network.msg;

import com.jiezhimod.network.ModNetwork;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

/**
 * 服务端 -&gt; 客户端：喷气状态同步（是否正在喷气 + 当前热量比例）。
 *
 * 为什么必须发包：服务端 spawn 的粒子客户端看不到（粒子只在客户端渲染），
 * 且热量是服务端权威的，客户端自己算会和真实值漂移。
 */
public class MessageJetExhaust implements IMessage {

    private UUID playerId;
    private boolean firing;
    private float heatRatio;

    public MessageJetExhaust() {
        this.playerId = new UUID(0L, 0L);
        this.heatRatio = 0.0F;
    }

    public MessageJetExhaust(EntityPlayer player, boolean firing, float heatRatio) {
        this.playerId = player.getUniqueID();
        this.firing = firing;
        this.heatRatio = heatRatio;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.playerId = new UUID(buf.readLong(), buf.readLong());
        this.firing = buf.readBoolean();
        this.heatRatio = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.playerId.getMostSignificantBits());
        buf.writeLong(this.playerId.getLeastSignificantBits());
        buf.writeBoolean(this.firing);
        buf.writeFloat(this.heatRatio);
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public boolean isFiring() {
        return firing;
    }

    public float getHeatRatio() {
        return heatRatio;
    }

    public static class Handler implements IMessageHandler<MessageJetExhaust, IMessage> {
        @Override
        public IMessage onMessage(MessageJetExhaust message, MessageContext ctx) {
            // 包可能在渲染线程之外到达，统一丢回主线程处理
            Minecraft.getMinecraft().addScheduledTask(() -> {
                World w = Minecraft.getMinecraft().world;
                if (w == null) {
                    return;
                }
                com.jiezhimod.client.ClientJetState.set(
                        message.playerId, message.firing, message.heatRatio);
            });
            return null;
        }
    }
}
