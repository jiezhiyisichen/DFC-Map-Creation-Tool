package com.jiezhimod.network.msg;

import com.jiezhimod.client.ClientEffects;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MessageSubtitleClear implements IMessage {
    public MessageSubtitleClear() {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
    }

    @Override
    public void toBytes(ByteBuf buf) {
    }

    public static class Handler implements IMessageHandler<MessageSubtitleClear, IMessage> {
        @Override
        public IMessage onMessage(MessageSubtitleClear message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(ClientEffects::clearSubtitles);
            return null;
        }
    }
}

