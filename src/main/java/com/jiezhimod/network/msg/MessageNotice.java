package com.jiezhimod.network.msg;

import com.jiezhimod.client.notice.ClientNoticeManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;

/** 服务端 -> 客户端：推送一条通知 */
public class MessageNotice implements IMessage {

    private String title;
    private String body;

    public MessageNotice() {
    }

    public MessageNotice(String title, String body) {
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.title = readString(buf);
        this.body = readString(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        writeString(buf, this.title);
        writeString(buf, this.body);
    }

    // 和既有消息保持一致的长度前缀写法，避免按字符串抢占整个 buffer
    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        byte[] bytes = new byte[Math.max(0, len)];
        if (len > 0) {
            buf.readBytes(bytes);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public static class Handler implements IMessageHandler<MessageNotice, IMessage> {
        @Override
        public IMessage onMessage(MessageNotice message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    ClientNoticeManager.add(message.title, message.body)
            );
            return null;
        }
    }
}
