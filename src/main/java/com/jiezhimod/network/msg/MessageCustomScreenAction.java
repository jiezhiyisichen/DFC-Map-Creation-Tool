package com.jiezhimod.network.msg;

import com.jiezhimod.block.TileCustomScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：自定义显示屏 GUI 上的一个操作（开机 / 删除某条 / 清空）。
 *
 * 【为什么只有"操作"，没有"数据"】数据是服务端算的（服务端权威），
 * 客户端 GUI 显示的列表本来就是服务端广播过来的镜像；客户端只管发"我要做什么"，
 * 服务端改完再广播回来，界面下一帧自己就更新了。
 * 这样不存在"客户端先斩后奏改了本地列表、服务端却拒绝了"的不一致。
 */
public class MessageCustomScreenAction implements IMessage {

    /** 切换开关 */
    public static final int ACT_POWER = 0;
    /** 删除第 index 条数据槽 */
    public static final int ACT_DELETE = 1;
    /** 清空全部数据槽 */
    public static final int ACT_CLEAR = 2;

    private BlockPos pos;
    private int action;
    private int index;

    public MessageCustomScreenAction() {
        this.pos = BlockPos.ORIGIN;
    }

    public MessageCustomScreenAction(BlockPos pos, int action, int index) {
        this.pos = pos;
        this.action = action;
        this.index = index;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.action = buf.readInt();
        this.index = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeInt(this.action);
        buf.writeInt(this.index);
    }

    // ==================== 便于自测 ====================

    public BlockPos pos() {
        return pos;
    }

    public int action() {
        return action;
    }

    public int index() {
        return index;
    }

    public static class Handler implements IMessageHandler<MessageCustomScreenAction, IMessage> {        @Override
        public IMessage onMessage(MessageCustomScreenAction msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            // 服务端主线程执行：方块实体不是线程安全的
            player.getServerWorld().addScheduledTask(() -> {
                TileEntity te = player.world.getTileEntity(msg.pos);
                if (!(te instanceof TileCustomScreen)) {
                    return;
                }
                TileCustomScreen screen = (TileCustomScreen) te;
                // 距离校验：别让改包的客户端隔着一百格外去删别人基地里那块屏的槽位
                if (player.getDistanceSq(msg.pos.getX() + 0.5D, msg.pos.getY() + 0.5D,
                        msg.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                    return;
                }
                switch (msg.action) {
                    case ACT_POWER:
                        // togglePower 内部已经 refresh + 广播了
                        screen.togglePower(player);
                        return;
                    case ACT_DELETE:
                        screen.removeSlot(msg.index);
                        break;
                    case ACT_CLEAR:
                        screen.clearSlots();
                        break;
                    default:
                        return;
                }
                // 删/清之后立刻广播一次，避免 GUI 里还要等一个刷新周期才看到变化
                screen.refresh();
            });
            return null;
        }
    }
}
