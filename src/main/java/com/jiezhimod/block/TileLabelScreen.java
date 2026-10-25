package com.jiezhimod.block;

import com.jiezhimod.dfc.DfcSnapshot;
import com.jiezhimod.dfc.LabelLayout;
import net.minecraft.nbt.NBTTagCompound;

/**
 * 标注显示器的方块实体：**七个格子的指标绑定**由玩家决定。
 *
 * 【与其它四块屏的关系】外壳、多方块、开机动画、网络通道、数据读取全部继承
 * {@link TileDfcScreen}，本类只多做一件事：把"第几格显示什么"随快照广播出去。
 *
 * 【为什么指标存在服务端】和自定义屏的数据槽一样，配置属于世界状态：
 * 存进 NBT（区块重载还在）、改动要能在多人下被其它玩家看到。
 * 客户端那份只是 {@code getData().labelMetrics} 的镜像，绝不本地改 ——
 * 否则界面会先"看起来成功了"，而服务端拒绝时没有任何提示。
 */
public class TileLabelScreen extends TileDfcScreen {

    /** 出厂配置之外的玩家配置（服务端权威） */
    private int[] metrics = LabelLayout.defaults();

    /**
     * 界面上该显示的那份配置。
     *
     * 服务端读自己的数组；客户端读同步过来的镜像 —— 和
     * {@code TileCustomScreen.displaySlots()} 是同一套约定。
     */
    public int[] getMetrics() {
        if (this.world != null && this.world.isRemote) {
            return getData().labelMetrics;
        }
        return this.metrics;
    }

    /** 第 slot 格当前绑的指标（越界返回 {@link LabelLayout#M_NONE}） */
    public int metricAt(int slot) {
        int[] m = getMetrics();
        return slot >= 0 && slot < m.length ? m[slot] : LabelLayout.M_NONE;
    }

    /**
     * 设置某一格的指标（服务端调用，网络包改完立刻刷一帧）。
     *
     * @return 是否真的变了；没变就不必重发，省一次全量广播
     */
    public boolean setMetric(int slot, int metric) {
        if (slot < 0 || slot >= LabelLayout.SLOTS) {
            return false;
        }
        int m = LabelLayout.clampMetric(metric);
        int[] arr = this.metrics;
        if (arr[slot] == m) {
            return false;
        }
        arr[slot] = m;
        this.markDirty();
        // 立刻刷一帧：否则玩家改完要等一个刷新周期才看到，会以为没生效
        this.refresh();
        return true;
    }

    /** 一键恢复出厂配置（界面上那个「重置」按钮） */
    public void resetMetrics() {
        this.metrics = LabelLayout.defaults();
        this.markDirty();
        this.refresh();
    }

    /**
     * 每次刷新前把玩家配置塞进快照 —— 客户端画画只看快照，不碰方块实体。
     *
     * 【为什么每次刷新都塞】配置可能被指令/调试棒/其它玩家改，
     * 与其维护"配置变更 -> 定向发包"的路由，不如让七个字节跟着既有节拍走。
     * 七个 byte 相对一整个快照（几百字节）完全可以忽略。
     */
    @Override
    protected void fillExtra(DfcSnapshot d) {
        for (int i = 0; i < LabelLayout.SLOTS; i++) {
            d.labelMetrics[i] = LabelLayout.clampMetric(this.metrics[i]);
        }
    }

    // ==================== NBT ====================

    @Override
    public void readFromNBT(NBTTagCompound c) {
        super.readFromNBT(c);
        int[] def = LabelLayout.defaults();
        for (int i = 0; i < LabelLayout.SLOTS; i++) {
            // 旧存档没有这个键时 getInteger 会返回 0（= 空槽）——
            // 那样升级上来的屏会一片空白，所以**缺键就回退到出厂值**。
            this.metrics[i] = c.hasKey("LabelMetric" + i)
                    ? LabelLayout.clampMetric(c.getInteger("LabelMetric" + i))
                    : def[i];
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        super.writeToNBT(c);
        for (int i = 0; i < LabelLayout.SLOTS; i++) {
            c.setInteger("LabelMetric" + i, LabelLayout.clampMetric(this.metrics[i]));
        }
        return c;
    }
}
