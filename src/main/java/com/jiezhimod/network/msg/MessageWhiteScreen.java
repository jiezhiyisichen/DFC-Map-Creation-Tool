package com.jiezhimod.network.msg;

import com.jiezhimod.client.ClientEffects;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MessageWhiteScreen implements IMessage {
    private int ticks;
    private float alpha;

    public MessageWhiteScreen() {
    }

    public MessageWhiteScreen(int ticks, float alpha) {
        this.ticks = ticks;
        this.alpha = alpha;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.ticks = buf.readInt();
        this.alpha = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.ticks);
        buf.writeFloat(this.alpha);
    }

    public static class Handler implements IMessageHandler<MessageWhiteScreen, IMessage> {
        @Override
        public IMessage onMessage(MessageWhiteScreen message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientEffects.startWhiteScreen(message.ticks, message.alpha));
            return null;
        }
    }
}

