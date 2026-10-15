package com.jiezhimod.block;

import com.jiezhimod.dfc.DfcBridge;
import com.jiezhimod.dfc.DfcSnapshot;
import com.jiezhimod.dfc.DfcWatch;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义显示屏的方块实体：**最多 8 条"盯着的读数"**，由 GUI 增删。
 *
 * 【与核心屏的关系】它是 {@link TileDfcScreen} 的子类 —— 外壳、多方块、
 * 开机动画、网络通道全部复用，差别只有两处：
 *   1. 主界面画的是数据槽列表（{@link com.jiezhimod.dfc.ScreenLayout#PAGE_CUSTOM}）；
 *   2. 每个刷新周期多干一件事：把 8 个槽指向的设备重读一遍。
 *
 * 【为什么槽配置存在这里、读数每帧重算】
 * 坐标/种类/索引是玩家的配置，进 NBT，重启和区块重载都还在；
 * 读数（value/ratio/alive）是"设备当前状态"，存了没有意义 ——
 * 重启后先显示一个过期值，比显示"--"更糟。
 *
 * 【服务端权威】和核心屏一致：读数在服务端算好塞进快照广播，
 * 客户端只负责画。多块屏看到的同一台储罐读数因此必然一致。
 */
public class TileCustomScreen extends TileDfcScreen {

    /** 数据槽配置（服务端权威）。读数只是缓存，每 tick 会被重写 */
    private final List<DfcWatch> slots = new ArrayList<DfcWatch>();

    // ==================== 槽位管理（服务端调用） ====================

    /** 当前槽位（服务端配置；客户端请读 {@code getData().watches}，那是同步过来的镜像） */
    public List<DfcWatch> getSlots() {
        return this.slots;
    }

    /**
     * 接入一台设备：把它**每一项可读数据**都加成一条槽。
     *
     * 一台机器可能不止一项（多罐机器每个罐一项），所以这里循环加；
     * 但最多加到 {@link DfcWatch#MAX} 条，满了就停下 —— 需求就是"最多 8 个数据"。
     * 已经在列表里的（同坐标同种类同索引）不重复加，否则玩家连点两下会得到 16 条重复项。
     *
     * @return 实际新增条数
     */
    public int addDevice(TileEntity te) {
        List<DfcWatch> found = DfcBridge.probeDevice(te);
        int added = 0;
        for (int i = 0; i < found.size(); i++) {
            if (this.slots.size() >= DfcWatch.MAX) {
                break;
            }
            DfcWatch w = found.get(i);
            boolean dup = false;
            for (int j = 0; j < this.slots.size(); j++) {
                if (this.slots.get(j).sameAs(w)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            this.slots.add(w);
            added++;
        }
        if (added > 0) {
            this.markDirty();
        }
        return added;
    }

    /** 删除一条（GUI 里的"删除"按钮）。返回是否真的删掉了 */
    public boolean removeSlot(int index) {
        if (index < 0 || index >= this.slots.size()) {
            return false;
        }
        this.slots.remove(index);
        this.markDirty();
        return true;
    }

    /** 清空全部槽位 */
    public void clearSlots() {
        if (!this.slots.isEmpty()) {
            this.slots.clear();
            this.markDirty();
        }
    }

    /** 第 i 条槽（越界返回 null） */
    public DfcWatch slotAt(int index) {
        return index >= 0 && index < this.slots.size() ? this.slots.get(index) : null;
    }

    /**
     * 界面上应该显示的那份列表。
     *
     * 服务端读自己的配置（{@link #slots}）；**客户端读同步过来的镜像**
     * （{@code getData().watches}）。客户端那份 {@code slots} 永远是空的 ——
     * 它只在服务端被填写，客户端拿到的是广播里带过来的那一份。
     */
    public List<DfcWatch> displaySlots() {
        if (this.world != null && this.world.isRemote) {
            return getData().watches;
        }
        return this.slots;
    }

    // ==================== 刷新 ====================

    /**
     * 每个刷新周期（0.05 秒）把 8 个槽的设备重读一遍，塞进快照广播出去。
     *
     * 读不到的槽**不删**：设备被拆掉是玩家自己的操作，界面显示"离线"比
     * 悄悄消失一条更容易理解（玩家会想"我那条涡轮机怎么没了"）。
     */
    @Override
    protected void fillExtra(DfcSnapshot d) {
        d.watches.clear();
        if (this.world == null) {
            return;
        }
        for (int i = 0; i < this.slots.size(); i++) {
            DfcWatch w = this.slots.get(i);
            DfcBridge.refreshWatch(this.world, w);
            // 塞副本：slots 是服务端的活对象，直接放进包里会让
            // "下一次刷新" 与 "正在序列化的这一帧" 指向同一批对象。
            d.watches.add(w.copy());
        }
    }

    // ==================== NBT ====================

    @Override
    public void readFromNBT(NBTTagCompound c) {
        super.readFromNBT(c);
        this.slots.clear();
        int n = c.getInteger("WatchCount");
        for (int i = 0; i < n && i < DfcWatch.MAX; i++) {
            String p = "Watch" + i + "_";
            if (!c.hasKey(p + "Kind")) {
                continue;
            }
            DfcWatch w = new DfcWatch();
            w.x = c.getInteger(p + "X");
            w.y = c.getInteger(p + "Y");
            w.z = c.getInteger(p + "Z");
            w.dim = c.getInteger(p + "Dim");
            w.kind = c.getInteger(p + "Kind");
            w.index = c.getInteger(p + "Idx");
            w.alive = false;   // 读数等下一次刷新
            this.slots.add(w);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        super.writeToNBT(c);
        c.setInteger("WatchCount", this.slots.size());
        for (int i = 0; i < this.slots.size(); i++) {
            DfcWatch w = this.slots.get(i);
            String p = "Watch" + i + "_";
            c.setInteger(p + "X", w.x);
            c.setInteger(p + "Y", w.y);
            c.setInteger(p + "Z", w.z);
            c.setInteger(p + "Dim", w.dim);
            c.setInteger(p + "Kind", w.kind);
            c.setInteger(p + "Idx", w.index);
        }
        return c;
    }
}
