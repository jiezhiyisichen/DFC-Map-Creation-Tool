package com.jiezhimod.network.msg;

import com.jiezhimod.client.ClientEffects;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;

public class MessageSubtitle implements IMessage {
    private int ticks;
    private String text;

    public MessageSubtitle() {
    }

    public MessageSubtitle(int ticks, String text) {
        this.ticks = ticks;
        this.text = text;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.ticks = buf.readInt();
        int len = buf.readInt();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        this.text = new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.ticks);
        byte[] bytes = this.text.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public static class Handler implements IMessageHandler<MessageSubtitle, IMessage> {
        @Override
        public IMessage onMessage(MessageSubtitle message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    ClientEffects.addSubtitle(message.text, message.ticks)
            );
            return null;
        }
    }
}

