package com.jiezhimod.ring;

import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageRingState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;

/**
 * 灯圈状态的**广播**（服务端 -> 附近客户端）。
 *
 * <p>【为什么单独一个类】"谁该收到、按几格分片、以哪一格为中心"这三件事
 * 在控制台触发和红石直连两条路上完全一样，抄两遍必然有一处漏了（漏了的表现就是
 * "这台控制台触发了，但画面不变色"）。所以只留这一份。</p>
 *
 * <p>见 {@link MessageRingState} 的类注释：**不要**指望
 * {@code world.notifyBlockUpdate} 会同步方块实体，它只发方块状态。</p>
 */
public final class RingNet {

    /** 广播半径（格）：整合包里灯圈基本都在同一片厂区，96 格够用且省流量 */
    public static final double RANGE = 96.0D;

    private RingNet() {
    }

    private static int dbgCount;

    /** 把一组灯圈的当前状态发给附近所有玩家（按 {@link MessageRingState#MAX_PER_PACKET} 分片） */
    public static void broadcast(World world, RingGroup group) {
        if (world == null || world.isRemote || group == null || group.members.length == 0) {
            return;
        }
        int dim = world.provider.getDimension();
        RingState state = group.state();
        // 以组里第一格为中心（组可能很长，用中心也没意义，取锚点足够）
        BlockPos center = BlockPos.fromLong(group.key);
        NetworkRegistry.TargetPoint point = new NetworkRegistry.TargetPoint(dim,
                center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D, RANGE);

        long[] all = group.members;
        // 排错用：一条日志说清"发给了多少格 / 什么颜色 / 亮不亮"。
        // 没有它就只能靠猜是"包没发"还是"tint 没生效"（两件事在画面上长得一样）。
        if (dbgCount++ < 40) {
            com.jiezhimod.JiezhiMod.warn("[Ring] broadcast " + all.length + " ring(s) color="
                    + RingColor.toHex(state.colorTo(0)) + (state.on ? " on" : " off")
                    + (state.blink ? " blink" : ""));
        }
        for (int off = 0; off < all.length; off += MessageRingState.MAX_PER_PACKET) {
            int n = Math.min(MessageRingState.MAX_PER_PACKET, all.length - off);
            long[] part = new long[n];
            System.arraycopy(all, off, part, 0, n);
            ModNetwork.CHANNEL.sendToAllAround(new MessageRingState(dim, state, part), point);
        }
    }
}
