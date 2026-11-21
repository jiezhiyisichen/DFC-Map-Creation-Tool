package com.jiezhimod.network.msg;

import com.jiezhimod.block.TileLightRing;
import com.jiezhimod.ring.RingState;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 服务端 -> 客户端：一组灯圈的**状态快照**（v4.4）。
 *
 * <h3>为什么非要自己发这个包（实机踩到的坑）</h3>
 * 第一版指望"改了方块状态 + {@code world.notifyBlockUpdate(pos, st, st, 3)} 就会把方块实体同步给客户端"，
 * 结果客户端灯圈**永远是白的**：翻原版源码才发现
 * {@code World.notifyBlockUpdate} 只是把事件转给 {@code IWorldEventListener}，
 * 服务端那条路最终发出去的是 {@code SPacketBlockChange} —— **只带方块状态，不带方块实体 NBT**。
 * 而 {@code SPacketUpdateTileEntity}（{@code getUpdatePacket}）只会随**区块数据**
 * （{@code SPacketChunkData}）在区块加载时发一次，运行期没有人替你发。
 * 所以运行期的状态变化必须自己发包 —— 本项目的 DFC 显示器一直是这么做的
 * （见 {@code MessageDfcScreen}），灯圈沿用同一套。
 *
 * <h3>一个包带一整组</h3>
 * 组有多达上千格，所以按 {@link #MAX_PER_PACKET} 分片发；触发是低频事件
 * （控制台被打一发 / 红石边沿），每片只有 8 字节 × N + 十几个字节的状态，代价可以忽略。
 */
public class MessageRingState implements IMessage {

    /** 一个包最多带多少格坐标（8 字节/格，256 格 ≈ 2KB） */
    public static final int MAX_PER_PACKET = 256;

    private int dim;
    private final RingState state = new RingState();
    private long[] positions = new long[0];

    public MessageRingState() {
        this.dim = 0;
    }

    public MessageRingState(int dim, RingState state, long[] positions) {
        this.dim = dim;
        if (state != null) {
            this.state.copyFrom(state);
        }
        this.positions = positions == null ? new long[0] : positions;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.dim = buf.readInt();
        this.state.readFrom(buf);
        int n = buf.readShort() & 0xFFFF;
        this.positions = new long[n];
        for (int i = 0; i < n; i++) {
            this.positions[i] = buf.readLong();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.dim);
        this.state.writeTo(buf);
        int n = Math.min(this.positions.length, MAX_PER_PACKET);
        buf.writeShort(n);
        for (int i = 0; i < n; i++) {
            buf.writeLong(this.positions[i]);
        }
    }

    public static class Handler implements IMessageHandler<MessageRingState, IMessage> {
        @SideOnly(Side.CLIENT)
        @Override
        public IMessage onMessage(MessageRingState msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                World world = Minecraft.getMinecraft().world;
                if (world == null || world.provider.getDimension() != msg.dim) {
                    return;
                }
                long now = world.getTotalWorldTime();
                for (long p : msg.positions) {
                    TileEntity te = world.getTileEntity(BlockPos.fromLong(p));
                    if (te instanceof TileLightRing) {
                        ((TileLightRing) te).applySync(msg.state, now);
                    }
                }
            });
            return null;
        }
    }
}
