package com.jiezhimod.network.msg;

import com.jiezhimod.block.ContainerCustomScreen;
import com.jiezhimod.block.TileCustomScreen;
import com.jiezhimod.item.ItemDfcRemote;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：自定义显示屏 GUI 上的一个操作（开机 / 删除某条 / 清空 / **导入遥控器**）。
 *
 * 【为什么只有"操作"，没有"数据"】数据是服务端算的（服务端权威），
 * 客户端 GUI 显示的列表本来就是服务端广播过来的镜像；客户端只管发"我要做什么"，
 * 服务端改完再广播回来，界面下一帧自己就更新了。
 * 这样不存在"客户端先斩后奏改了本地列表、服务端却拒绝了"的不一致。
 *
 * {@link #ACT_IMPORT} 也一样：客户端只说"我点了添加"，
 * **遥控器从哪个槽里读、读出来是什么，全由服务端自己去看**（玩家的 openContainer）。
 */
public class MessageCustomScreenAction implements IMessage {

    /** 切换开关 */
    public static final int ACT_POWER = 0;
    /** 删除第 index 条数据槽 */
    public static final int ACT_DELETE = 1;
    /** 清空全部数据槽 */
    public static final int ACT_CLEAR = 2;
    /** 把遥控器槽里那支遥控器记录到的设备**全部**接进来 */
    public static final int ACT_IMPORT = 3;

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
                    case ACT_IMPORT:
                        importFromRemote(player, screen);
                        // importFromRemote 里已经 refresh + 广播过，别再来一遍
                        return;
                    default:
                        return;
                }
                // 删/清之后立刻广播一次，避免 GUI 里还要等一个刷新周期才看到变化
                screen.refresh();
            });
            return null;
        }

        /**
         * 把 GUI 槽位里那支遥控器记录到的设备全部接进来。
         *
         * 【为什么从 openContainer 里拿遥控器，而不是从包里传过来】
         * 包里只放了"我要添加"这四个字。遥控器到底是什么、记录了什么，
         * 服务端自己看玩家当前开着的那个容器 —— 客户端伪造不了一个它没持有的物品。
         * 这也是这套"只发操作不发数据"的一贯做法。
         */
        private static void importFromRemote(EntityPlayerMP player, TileCustomScreen screen) {
            if (!(player.openContainer instanceof ContainerCustomScreen)) {
                return;
            }
            ContainerCustomScreen c = (ContainerCustomScreen) player.openContainer;
            // 防串台：玩家可能同时开着别的地方的另一块自定义屏
            if (c.getTile() != screen) {
                return;
            }
            ItemStack remote = c.getRemoteStack();
            if (remote.isEmpty() || !(remote.getItem() instanceof ItemDfcRemote)) {
                return;
            }
            int[] r = screen.importDevices(remote);
            screen.refresh();
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + ItemDfcRemote.summarize(r, screen.getSlots().size())), false);
        }
    }
}
