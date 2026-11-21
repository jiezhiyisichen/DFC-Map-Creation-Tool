package com.jiezhimod.network.msg;

import com.jiezhimod.block.TileRingConsole;
import com.jiezhimod.ring.RingConsoleConfig;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 服务端 -> 客户端：灯圈控制台的整份状态（配置 + 绑定 + 上次触发结果）。
 *
 * <h3>为什么非要单独发（v4.4 实机 bug）</h3>
 * 原来服务端改完配置只调 {@code world.notifyBlockUpdate(pos, st, st, 3)}。
 * 那条路最终只发 <b>{@code SPacketBlockChange}</b>（只带方块状态），
 * <b>不带方块实体 NBT</b>；而 {@code SPacketUpdateTileEntity} 只在**区块加载**时
 * 随 {@code SPacketChunkData} 发一次，运行期没人替你发。
 *
 * <p>后果玩家看到的是：界面里配好颜色按「应用」→ 关掉再开**又变回默认**；
 * 灯圈也永远不换色（因为 GUI 下次打开时是从**客户端**那份默认配置开始编辑的，
 * 再按一次「应用」就把服务端那份好配置覆盖掉了）。</p>
 *
 * <p>所以运行期的状态变化必须自己发包 —— 和 {@link MessageRingState} 同一个结论。</p>
 */
public class MessageRingConsoleSync implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private final RingConsoleConfig config = new RingConsoleConfig();
    private BlockPos ringPos;
    private String last = "";

    public MessageRingConsoleSync() {
    }

    public MessageRingConsoleSync(BlockPos pos, RingConsoleConfig config,
                                  BlockPos ringPos, String last) {
        this.pos = pos;
        this.ringPos = ringPos;
        this.last = last == null ? "" : last;
        if (config != null) {
            this.config.copyFrom(config);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.config.readFrom(buf);
        boolean bound = buf.readBoolean();
        this.ringPos = bound ? new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()) : null;
        this.last = readString(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        this.config.writeTo(buf);
        buf.writeBoolean(this.ringPos != null);
        if (this.ringPos != null) {
            buf.writeInt(this.ringPos.getX());
            buf.writeInt(this.ringPos.getY());
            buf.writeInt(this.ringPos.getZ());
        }
        writeString(buf, this.last);
    }

    /** 变长字符串：先写字节数再写 UTF-8 字节（省得为几个字带上整包长度） */
    private static void writeString(ByteBuf buf, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeShort(b.length);
        buf.writeBytes(b);
    }

    private static String readString(ByteBuf buf) {
        int n = buf.readShort() & 0xFFFF;
        if (n <= 0) {
            return "";
        }
        byte[] b = new byte[n];
        buf.readBytes(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static class Handler implements IMessageHandler<MessageRingConsoleSync, IMessage> {
        @SideOnly(Side.CLIENT)
        @Override
        public IMessage onMessage(MessageRingConsoleSync msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().world == null) {
                    return;
                }
                TileEntity te = Minecraft.getMinecraft().world.getTileEntity(msg.pos);
                if (te instanceof TileRingConsole) {
                    ((TileRingConsole) te).applySync(msg.config, msg.ringPos, msg.last);
                }
            });
            return null;
        }
    }
}
