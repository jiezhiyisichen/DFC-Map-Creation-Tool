package com.jiezhimod.ring;

import com.jiezhimod.JiezhiMod;
import com.jiezhimod.block.BlockLightRing;
import com.jiezhimod.block.TileLightRing;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 灯圈组的**服务端登记处**（每个维度一份）。
 *
 * <h3>只做三件事</h3>
 * <ol>
 *   <li><b>重建组</b>：放置 / 破坏任一灯圈时，从那格出发六向洪水填充，
 *       把连着的灯圈重新划组（{@link #rebuild}）；</li>
 *   <li><b>查询</b>：方块实体每刻问"我这格现在的状态是什么"
 *       （{@link #stateAt} / {@link #litAt}）；</li>
 *   <li><b>下发</b>：控制台 / 红石触发时改组状态（{@link #applyTo}）。</li>
 * </ol>
 *
 * <h3>为什么不做 per-tick 的全局 tick</h3>
 * 淡入淡出的颜色是客户端按参数自己算的（见 {@link RingState}），闪烁的亮灭
 * 由**每格自己的方块实体**在 {@code update()} 里问"此刻亮不亮"来翻方块状态。
 * 也就是说：没有事件就不需要有人跑 —— 状态只在"被触发"的那一刻变一次。
 * 少一个全局 tick 器，就少一类"服务端重启后计时器丢了"的 bug。
 *
 * <h3>日志</h3>
 * 组重建 / 控制台触发都走 {@link #dbg}，**只打前 N 条 + 状态变化**，
 * 大量刷屏反而把真正有用的信息淹掉（用户点名"加上日志调试，避免大量 token 用来修 bug"）。
 */
public final class RingGroupManager {

    private static final Map<Integer, RingGroupManager> BY_DIM = new HashMap<Integer, RingGroupManager>();

    /** 组键 → 组 */
    private final Map<Long, RingGroup> groups = new HashMap<Long, RingGroup>();
    /** 成员坐标 → 组键（查询 O(1)，不用每刻做洪水填充） */
    private final Map<Long, Long> memberToGroup = new HashMap<Long, Long>();

    /**
     * "静默期"计数：正在翻 {@code POWERED} 的时候，邻居收到的 {@code neighborChanged}
     * 不该再触发洪水填充。
     *
     * <p>【为什么必须有】{@code World#setBlockState} 会把 6 个邻居的
     * {@code neighborChanged} 全叫一遍。一组 N 格的灯带同时亮 / 灭（闪烁时每半个周期
     * 都发生一次）就是 N 次 setBlockState → 最多 6N 次邻居回调 → 每次回调又做一遍
     * O(N) 的洪水填充 + 一堆 HashMap/BlockPos 分配。N=60 时一次闪烁就要几万次分配，
     * 表现出来就是"灯圈一闪，画面顿一下"。
     * 而"自己翻 POWERED"根本不改变组的成员关系（方块还是那个方块），所以这些重建
     * 全是白做的 —— 用这个计数器把它们挡掉。</p>
     *
     * <p>只在服务端主线程用，不需要同步。</p>
     */
    private static int quiet;

    /** 进入静默期（{@link #endQuiet} 必须放 finally 里） */
    public static void beginQuiet() {
        quiet++;
    }

    public static void endQuiet() {
        if (quiet > 0) {
            quiet--;
        }
    }

    /** 静默期内：邻居的 {@code neighborChanged} 不重建组 */
    public static boolean quiet() {
        return quiet > 0;
    }

    private RingGroupManager() {
    }

    /** 取某维度的登记处（**仅服务端**调用） */
    public static synchronized RingGroupManager of(World world) {
        int dim = world.provider.getDimension();
        RingGroupManager m = BY_DIM.get(dim);
        if (m == null) {
            m = new RingGroupManager();
            BY_DIM.put(dim, m);
        }
        return m;
    }

    // ==================== 查询 ====================

    /** 这格所属的组（没入组返回 null —— 新放的方块在 rebuild 之前会短暂处于这种状态） */
    public RingGroup groupAt(BlockPos pos) {
        Long key = this.memberToGroup.get(pos.toLong());
        return key == null ? null : this.groups.get(key);
    }

    /** 这格此刻的状态快照（没入组就给一份默认的"灭"） */
    public RingState snapshotAt(World world, BlockPos pos) {
        RingGroup g = this.groupAt(pos);
        return g == null ? new RingState() : g.snapshot(world.getTotalWorldTime());
    }

    /** 这格此刻算不算亮（方块实体用来翻 POWERED） */
    public boolean litAt(World world, BlockPos pos) {
        RingGroup g = this.groupAt(pos);
        return g != null && g.litAt(world.getTotalWorldTime());
    }

    // ==================== 重建 ====================

    /**
     * 从 {@code anchor} 出发重建它所在的组。
     *
     * <p>【为什么全组重算而不是增量维护】增量维护（只改被拆那格的邻居）在
     * "拆掉桥接格把一组拆成两半"时必然算错，而灯圈组最多
     * {@link RingGroup#MAX_MEMBERS} 格，整组洪水填充的成本可以忽略。</p>
     */
    public void rebuild(World world, BlockPos anchor) {
        int dim = world.provider.getDimension();
        List<Long> found = new ArrayList<Long>();
        Deque<BlockPos> queue = new ArrayDeque<BlockPos>();
        java.util.Set<Long> seen = new java.util.HashSet<Long>();
        queue.add(anchor);
        seen.add(anchor.toLong());
        while (!queue.isEmpty() && found.size() < RingGroup.MAX_MEMBERS) {
            BlockPos p = queue.poll();
            found.add(p.toLong());
            for (net.minecraft.util.EnumFacing f : net.minecraft.util.EnumFacing.values()) {
                BlockPos n = p.offset(f);
                if (!seen.add(n.toLong())) {
                    continue;
                }
                if (world.getBlockState(n).getBlock() instanceof BlockLightRing) {
                    if (found.size() < RingGroup.MAX_MEMBERS) {
                        queue.add(n);
                    }
                }
            }
        }
        if (queue.isEmpty() && found.size() >= RingGroup.MAX_MEMBERS) {
            dbg(dim, "ring group hit the member cap " + RingGroup.MAX_MEMBERS
                    + " at " + anchor + " (extra rings ignored)");
        }

        long key = found.isEmpty() ? anchor.toLong() : minKey(found);
        RingGroup group = this.groups.get(key);
        if (group == null) {
            long[] arr = new long[found.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = found.get(i);
            }
            group = new RingGroup(dim, key, arr);
            this.groups.put(key, group);
        }

        // 成员重新登记；登记前先记下它们原来属于哪个组，方便把空组清掉
        java.util.Set<Long> orphanCandidates = new java.util.HashSet<Long>();
        for (long p : found) {
            Long old = this.memberToGroup.get(p);
            if (old != null && old != key) {
                orphanCandidates.add(old);
            }
            this.memberToGroup.put(p, key);
            TileEntity te = world.getTileEntity(BlockPos.fromLong(p));
            if (te instanceof TileLightRing) {
                ((TileLightRing) te).setGroupKey(key);
            }
        }
        Iterator<Map.Entry<Long, Long>> it = this.memberToGroup.entrySet().iterator();
        while (it.hasNext()) {
            Long gk = it.next().getValue();
            orphanCandidates.remove(gk);
        }
        for (Long dead : orphanCandidates) {
            RingGroup g = this.groups.remove(dead);
            if (g != null) {
                dbg(dim, "ring group " + g.members.length + " -> dissolved (split)");
            }
        }
        dbg(dim, "ring group rebuilt: " + found.size() + " ring(s) @ " + BlockPos.fromLong(key));
    }

    private static long minKey(List<Long> list) {
        long min = Long.MAX_VALUE;
        for (long v : list) {
            if (v < min) {
                min = v;
            }
        }
        return min;
    }

    // ==================== 下发 ====================

    /**
     * 把一条控制台配置打到 {@code ringPos} 所在的组上。
     *
     * @return 给玩家看的一句话；组不存在 / 配置为空时返回 null（调用方提示"没绑上"）
     */
    public String applyTo(World world, BlockPos ringPos, RingConsoleConfig cfg, String consoleName) {
        RingGroup g = this.groupAt(ringPos);
        if (g == null) {
            // 还没入组（比如刚放完一格、方块实体还没跑过第一刻）：当场补一次重建
            this.rebuild(world, ringPos);
            g = this.groupAt(ringPos);
            if (g == null) {
                return null;
            }
        }
        if (cfg == null || cfg.isEmpty()) {
            return null;
        }
        long now = world.getTotalWorldTime();
        RingState next = cfg.apply(g.snapshot(now));
        boolean changed = g.setState(next, now);
        // 状态变了就通知客户端（运行期只有这一条路能把颜色送到客户端，见 MessageRingState）
        if (changed) {
            RingNet.broadcast(world, g);
        }
        dbg(world.provider.getDimension(), consoleName + " -> " + g.members.length
                + " ring(s): " + cfg.summary() + (changed ? "" : " (no change)"));
        return cfg.summary() + "  [" + g.members.length + " 格]";
    }

    // ==================== 日志 ====================

    private static int logged;

    /** 排错日志：前 40 条全打，之后只打"组重建 / 状态变化"这种低频事件 */
    private static void dbg(int dim, String msg) {
        if (logged++ < 40) {
            JiezhiMod.warn("[Ring@" + dim + "] " + msg);
        }
    }
}
