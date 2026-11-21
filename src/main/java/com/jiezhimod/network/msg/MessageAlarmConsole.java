package com.jiezhimod.network.msg;

import com.jiezhimod.alarm.AlarmConfig;
import com.jiezhimod.block.TileAlarmConsole;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 警示灯控制台的配置包（v4.5，**双向**）。
 *
 * <h3>为什么一个包干两件事</h3>
 * 客户端点一下就要保存，服务端保存完还要把"真正的结果"告诉客户端
 * （红石可能同时把 on 改掉了，客户端本地那份不能算数）。
 * 与其写两个字段几乎一样的包，不如加一个 {@link #request} 标记：
 * <ul>
 *   <li>{@code request = true}（客户端 → 服务端）：请把这份配置存下来并推给灯；</li>
 *   <li>{@code request = false}（服务端 → 客户端）：这是权威配置，照抄到本地方块实体。</li>
 * </ul>
 *
 * <p>【为什么要自己发包】{@code notifyBlockUpdate} 只同步<b>方块状态</b>，
 * 不带方块实体 NBT —— 运行期改配置客户端根本收不到（v4.4 灯圈控制台在这上面翻过车）。</p>
 */
public class MessageAlarmConsole implements IMessage {

    private BlockPos pos;
    private AlarmConfig cfg;
    private boolean request;

    public MessageAlarmConsole() {
    }

    public MessageAlarmConsole(BlockPos pos, AlarmConfig cfg, boolean request) {
        this.pos = pos;
        this.cfg = cfg;
        this.request = request;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.cfg = new AlarmConfig();
        this.cfg.color = buf.readInt() & 0xFFFFFF;
        this.cfg.rpm = buf.readShort() & 0xFFFF;
        this.cfg.on = buf.readBoolean();
        this.request = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeInt(this.cfg.color & 0xFFFFFF);
        buf.writeShort(this.cfg.rpm);
        buf.writeBoolean(this.cfg.on);
        buf.writeBoolean(this.request);
    }

    /** 客户端收到权威配置 */
    public static class ClientHandler implements IMessageHandler<MessageAlarmConsole, IMessage> {
        @Override
        public IMessage onMessage(MessageAlarmConsole msg, MessageContext ctx) {
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                net.minecraft.client.multiplayer.WorldClient world =
                        net.minecraft.client.Minecraft.getMinecraft().world;
                if (world == null || msg.pos == null) {
                    return;
                }
                TileEntity te = world.getTileEntity(msg.pos);
                if (te instanceof TileAlarmConsole) {
                    // 只改本地那份显示用配置，不要反过来再推给灯（客户端没有权威）
                    ((TileAlarmConsole) te).config().color = msg.cfg.color;
                    ((TileAlarmConsole) te).config().rpm = msg.cfg.rpm;
                    ((TileAlarmConsole) te).config().on = msg.cfg.on;
                }
            });
            return null;
        }
    }

    /** 服务端保存并立即应用到所有绑定的灯 */
    public static class ServerHandler implements IMessageHandler<MessageAlarmConsole, IMessage> {
        @Override
        public IMessage onMessage(MessageAlarmConsole msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                WorldServer world = player.getServerWorld();
                if (msg.pos == null || !world.isBlockLoaded(msg.pos)) {
                    return;
                }
                TileEntity te = world.getTileEntity(msg.pos);
                if (te instanceof TileAlarmConsole) {
                    ((TileAlarmConsole) te).setConfig(msg.cfg);
                }
            });
            return null;
        }
    }
}
