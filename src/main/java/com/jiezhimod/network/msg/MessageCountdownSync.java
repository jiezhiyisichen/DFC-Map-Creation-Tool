package com.jiezhimod.network.msg;

import com.jiezhimod.client.ClientCountdownManager;
import com.jiezhimod.countdown.Countdown;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：倒计时全量同步包（添加/删除/暂停/继续/每秒纠正/玩家加入时发送）。
 */
public class MessageCountdownSync implements IMessage {
    private List<Countdown> list;

    public MessageCountdownSync() {
    }

    public MessageCountdownSync(List<Countdown> list) {
        this.list = list;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = buf.readInt();
        list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = readString(buf);
            int style = buf.readInt();
            float r = buf.readFloat();
            float g = buf.readFloat();
            float b = buf.readFloat();
            long ticks = buf.readLong();
            long totalTicks = buf.readLong();
            boolean paused = buf.readBoolean();
            boolean finished = buf.readBoolean();
            Countdown c = new Countdown(name, style, r, g, b, ticks);
            c.totalTicks = totalTicks > 0L ? totalTicks : Math.max(1L, ticks);
            c.paused = paused;
            c.finished = finished;
            list.add(c);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(list.size());
        for (Countdown c : list) {
            writeString(buf, c.name);
            buf.writeInt(c.style);
            buf.writeFloat(c.rgb[0]);
            buf.writeFloat(c.rgb[1]);
            buf.writeFloat(c.rgb[2]);
            buf.writeLong(c.remainingTicks);
            buf.writeLong(c.totalTicks);
            buf.writeBoolean(c.paused);
            buf.writeBoolean(c.finished);
        }
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static class Handler implements IMessageHandler<MessageCountdownSync, IMessage> {
        @Override
        public IMessage onMessage(MessageCountdownSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientCountdownManager.setFromServer(message.list));
            return null;
        }
    }
}
