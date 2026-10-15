package com.jiezhimod.network.msg;

import com.jiezhimod.block.TileDfcScreen;
import com.jiezhimod.dfc.DfcSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 -> 客户端：把显示器要显示的一整帧推过去。
 *
 * 一帧 = 开关状态 + 倾斜角 + DFC 数据快照 + 事件日志。
 * 日志整份发而不发增量：最多 9 条、每条几十字节，比维护"客户端漏包补发"的逻辑便宜得多。
 *
 * 注意（1.12.2）：{@link DfcSnapshot} 的读写顺序必须严格镜像，
 * 少写一个字段的症状是"客户端数值全错位"——温度显示成能量、时间显示成百分比，
 * 而且不会报任何异常，所以改动快照字段时务必同步检查 writeTo / readFrom。
 */
public class MessageDfcScreen implements IMessage {

    private BlockPos pos;
    private boolean powered;
    private int tilt;
    private DfcSnapshot data;
    private List<String> events;

    public MessageDfcScreen() {
        this.pos = BlockPos.ORIGIN;
        this.data = new DfcSnapshot();
        this.events = new ArrayList<String>();
    }

    public MessageDfcScreen(TileDfcScreen te) {
        this.pos = te.getPos();
        this.powered = te.isPowered();
        this.tilt = te.getTilt();
        this.data = DfcSnapshot.copied(te.getData());
        this.events = new ArrayList<String>(te.getEvents());
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.powered = buf.readBoolean();
        this.tilt = buf.readInt();
        this.data = new DfcSnapshot();
        this.data.readFrom(buf);
        int n = buf.readInt();
        this.events = new ArrayList<String>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            int len = buf.readUnsignedByte();
            byte[] b = new byte[len];
            buf.readBytes(b);
            this.events.add(new String(b, StandardCharsets.UTF_8));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeBoolean(this.powered);
        buf.writeInt(this.tilt);
        this.data.writeTo(buf);
        List<String> ev = this.events == null ? new ArrayList<String>() : this.events;
        buf.writeInt(ev.size());
        for (String s : ev) {
            byte[] b = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
            int len = Math.min(b.length, 255);
            buf.writeByte(len);
            buf.writeBytes(b, 0, len);
        }
    }

    public static class Handler implements IMessageHandler<MessageDfcScreen, IMessage> {
        @Override
        public IMessage onMessage(MessageDfcScreen message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().world == null) {
                    return;
                }
                TileEntity te = Minecraft.getMinecraft().world.getTileEntity(message.pos);
                if (te instanceof TileDfcScreen) {
                    ((TileDfcScreen) te).applyServerState(
                            message.powered, message.tilt, message.data, message.events);
                }
            });
            return null;
        }
    }
}
