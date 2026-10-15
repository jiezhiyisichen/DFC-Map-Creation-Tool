package com.jiezhimod.network.msg;

import com.jiezhimod.client.ClientEffects;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MessageCameraShake implements IMessage {
    private int ticks;
    private float intensity;

    public MessageCameraShake() {
    }

    public MessageCameraShake(int ticks, float intensity) {
        this.ticks = ticks;
        this.intensity = intensity;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.ticks = buf.readInt();
        this.intensity = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.ticks);
        buf.writeFloat(this.intensity);
    }

    public static class Handler implements IMessageHandler<MessageCameraShake, IMessage> {
        @Override
        public IMessage onMessage(MessageCameraShake message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    ClientEffects.startShake(message.ticks, message.intensity)
            );
            return null;
        }
    }
}

