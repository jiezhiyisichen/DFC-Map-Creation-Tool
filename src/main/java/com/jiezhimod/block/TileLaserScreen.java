package com.jiezhimod.block;

import com.jiezhimod.dfc.DfcBridge;
import com.jiezhimod.dfc.DfcSnapshot;
import com.jiezhimod.dfc.DfcWatch;
import com.jiezhimod.dfc.LaserInfo;
import com.jiezhimod.dfc.LaserLayout;
import net.minecraft.tileentity.TileEntity;

import java.util.List;

/**
 * 激光状态显示器的方块实体：**最多四台激光**，一台一行（格）。
 *
 * 【与自定义屏的关系】它就是 {@link TileCustomScreen} 的子类 —— 遥控器记录设备、
 * 放进 GUI 槽位点「添加」、每行一个「删除」按钮，这一整套交互原样继承。
 * 差别只有三处：
 *   1. {@link #maxSlots()} = 4（自定义屏是 8 条，激光每台要占五行读数，塞不下）；
 *   2. **一台设备只占一个槽**（自定义屏会把一台多罐机器的每个罐拆成一条）；
 *   3. 刷新时除了设备本身，还要把"这台激光该显示哪五行"算好塞进快照。
 *
 * 【为什么读数在服务端拼】客户端拿不到 HBM 的类，也读不到透镜耐久这种
 * 在物品栏里的东西；服务端算好 {@link LaserInfo#metrics} 之后，客户端只是照着画。
 */
public class TileLaserScreen extends TileCustomScreen {

    // ==================== 耗尽时间（实测斜率） ====================
    //
    // 需求："计算耗尽时间在弹窗上"。冷凝胶 / 透镜耐久的消耗速度是 HBM 内部逻辑，
    // 猜公式一定会随版本漂；这里改成**量**：记住上一次读到的比例与当时的刻数，
    // 两次一减就是每秒掉多少，再拿当前值除一下。
    //   · 读数没在掉（速度 ≤ 0）→ 不给时间（弹窗上写"消耗速度未知"），绝不瞎报；
    //   · 每台设备各记各的（下标就是槽位）；
    //   · 只在服务端算（客户端没有权威数据，也不该重复算）。

    /** 上一次读到的比例；< 0 = 还没采到 */
    private final float[] lastRatio = {-1.0F, -1.0F, -1.0F, -1.0F};
    /** 上一次采样时的世界刻 */
    private final long[] lastSampleTick = {0L, 0L, 0L, 0L};

    /**
     * 给一台设备算"还有多久耗尽"。
     *
     * @param slot 槽位下标（0..{@link LaserLayout#SLOTS}-1）
     * @param lab  刚读出来的这一台
     */
    private void applyEta(int slot, LaserInfo lab) {
        lab.etaSeconds = -1;
        if (this.world == null || slot < 0 || slot >= this.lastRatio.length) {
            return;
        }
        int row = lab.warnRow;
        if (row < 0 || row >= lab.metrics.size()) {
            this.lastRatio[slot] = -1.0F;
            return;
        }
        double ratio = lab.metrics.get(row).ratio;
        if (ratio < 0.0D || ratio > 1.0D) {
            this.lastRatio[slot] = -1.0F;
            return;
        }
        long now = this.world.getTotalWorldTime();
        float prev = this.lastRatio[slot];
        long prevTick = this.lastSampleTick[slot];
        if (prev >= 0.0F && now > prevTick && prev > ratio) {
            double perTick = (prev - ratio) / (double) (now - prevTick);
            if (perTick > 1.0E-9D) {
                long secs = Math.round(ratio / perTick / 20.0D);
                lab.etaSeconds = (int) Math.max(0L, Math.min(359999L, secs));
            }
        }
        this.lastRatio[slot] = (float) ratio;
        this.lastSampleTick[slot] = now;
    }

    /** 一台设备一个槽 */
    @Override
    public int maxSlots() {
        return LaserLayout.SLOTS;
    }

    /**
     * 默认刷新周期 200ms。
     *
     * 这一页的读数（透镜耐久、冷凝胶余量、储能）都是**分钟级**才动的东西，
     * 20Hz 刷新纯属白烧 CPU；而它一屏有四台设备、每台五行字，重绘反而比核心页更贵。
     */
    @Override
    protected int defaultRefreshMs() {
        return 200;
    }

    /**
     * 接入一台设备（服务端）。
     *
     * 【为什么整段覆盖而不是复用父类】父类会把"每项可读数据"都加成一条
     * （储液罐的每个罐各一条），那是自定义屏的语义；这里一个槽就是一台激光，
     * 一台燃料喷射器有俩罐也只占一个槽，否则"最多四台"立刻变成"最多两台"。
     *
     * @return 实际新增条数（0 = 满了 / 已经加过 / 不是可读设备）
     */
    @Override
    public int addDevice(TileEntity te) {        if (te == null) {
            return 0;
        }
        TileEntity dev = DfcBridge.resolveProxy(te);
        if (dev == null) {
            return 0;
        }
        // 【不再按"是不是 DFC 激光"过滤】实机反馈过"加了却什么都没出现"：
        // 玩家用遥控器记的可能是外壳格、可能是别的机器，直接拒掉会让人以为功能坏了。
        // 现在一律收下，读不出激光数据时走通用页（把能读到的开关/电量/罐列出来）。
        int dim = this.world == null ? Integer.MIN_VALUE : this.world.provider.getDimension();
        net.minecraft.util.math.BlockPos p = dev.getPos();
        List<DfcWatch> slots = getSlots();
        for (int i = 0; i < slots.size(); i++) {
            DfcWatch w = slots.get(i);
            if (w.x == p.getX() && w.y == p.getY() && w.z == p.getZ() && w.dim == dim) {
                return 0;   // 同一台设备不重复加
            }
        }
        if (slots.size() >= maxSlots()) {
            return 0;
        }
        slots.add(new DfcWatch(p.getX(), p.getY(), p.getZ(), dim, DfcWatch.KIND_NONE, 0));
        this.markDirty();
        return 1;
    }

    /**
     * 从**激光链接器**里把记录到的设备接进来（覆盖父类的遥控器版本）。
     *
     * 【为什么必须覆盖】父类读的是 {@code ItemDfcRemote} 的 NBT 键（{@code DfcDevCount} 等）。
     * 激光屏只认激光链接器，两者是不同的物品、不同的键 —— 不覆盖的话
     * GUI 里点「添加」永远读不到东西（链接器里明明记了 3 台，界面毫无反应）。
     *
     * @return {新接入台数, 跳过台数（满了/已接过）, 失联台数（换维度/机器没了）}
     */
    @Override
    public int[] importDevices(net.minecraft.item.ItemStack stack) {
        int added = 0;
        int skipped = 0;
        int missing = 0;
        if (this.world == null) {
            return new int[]{0, 0, 0};
        }
        int dim = this.world.provider.getDimension();
        int n = com.jiezhimod.item.ItemLaserConnector.deviceCount(stack);
        for (int i = 0; i < n; i++) {
            net.minecraft.util.math.BlockPos dp =
                    com.jiezhimod.item.ItemLaserConnector.deviceAt(stack, i);
            if (dp == null) {
                continue;
            }
            if (com.jiezhimod.item.ItemLaserConnector.deviceDim(stack, i) != dim) {
                missing++;
                continue;
            }
            // 记录时存的是设备自己的坐标；万一那台机器后来被换成外壳块，这里再代理解一次链
            TileEntity dev = DfcBridge.resolveProxy(this.world.getTileEntity(dp));
            if (DfcBridge.laserKind(dev) == LaserInfo.KIND_NONE) {
                missing++;
                continue;
            }
            if (addDevice(dev) == 0) {
                skipped++;   // 满了，或者这台已经接过了
            } else {
                added++;
            }
        }
        return new int[]{added, skipped, missing};
    }

    /**
     * 每个刷新周期把四台激光重读一遍。
     *
     * 【两趟走】第一趟只判类型，好把 SL1 / SL2 / PL1 … 按添加顺序编出来；
     * 第二趟才真正读数据 —— 因为"换透镜 SL2"这类弹窗文案里要带编号，
     * 编号必须在读之前就定下来。
     */
    @Override
    protected void fillExtra(DfcSnapshot d) {
        d.watches.clear();
        d.lasers.clear();
        if (this.world == null) {
            return;
        }
        List<DfcWatch> slots = getSlots();
        int[] nth = new int[8];   // 每种类型已经编到几号（下标 = kind）
        for (int i = 0; i < slots.size() && i < LaserLayout.SLOTS; i++) {
            DfcWatch slot = slots.get(i);
            TileEntity dev = this.world.getTileEntity(new net.minecraft.util.math.BlockPos(
                    slot.x, slot.y, slot.z));

            LaserInfo lab = new LaserInfo();
            int kind = DfcBridge.laserKind(dev);
            if (kind == LaserInfo.KIND_NONE) {
                // 设备被拆了：槽位保留（玩家自己删），画面显示"离线"
                lab.kind = LaserInfo.KIND_NONE;
                lab.alive = false;
                lab.label = "—";
                lab.device = "离线";
                d.lasers.add(lab);
                d.watches.add(summaryOf(slot, lab));
                continue;
            }
            if (kind < nth.length) {
                nth[kind]++;
                lab.label = LaserLayout.labelFor(kind, nth[kind]);
            }
            DfcBridge.readLaser(dev, lab);
            applyEta(i, lab);
            d.lasers.add(lab);
            d.watches.add(summaryOf(slot, lab));
        }
    }

    /**
     * 给 GUI 列表用的一行摘要（GUI 是通用组件，只认 DfcWatch）。
     *
     * 名字写"SL1 稳定器"，数据名与读数取这台激光的第一行 —— 点「删除」时
     * 玩家能一眼看出删的是哪一台。
     */
    private static DfcWatch summaryOf(DfcWatch slot, LaserInfo lab) {
        DfcWatch w = new DfcWatch(slot.x, slot.y, slot.z, slot.dim, DfcWatch.KIND_NONE, 0);
        w.device = lab.label + " " + LaserLayout.kindName(lab.kind);
        w.alive = lab.alive;
        if (lab.metrics.isEmpty()) {
            w.name = "";
            w.value = lab.alive ? "--" : "离线";
            w.ratio = -1.0D;
        } else {
            LaserInfo.LaserMetric m = lab.metrics.get(0);
            w.name = m.name;
            w.value = m.value;
            w.ratio = m.ratio;
        }
        return w;
    }

    /** 界面上该显示的那份槽位（客户端读同步镜像，与自定义屏同款约定） */
    @Override
    public List<DfcWatch> displaySlots() {
        return super.displaySlots();
    }
}
