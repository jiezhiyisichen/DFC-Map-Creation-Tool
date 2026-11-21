package com.jiezhimod.alarm;

import net.minecraft.nbt.NBTTagCompound;

/**
 * 旋转警示灯的一份配置（纯数据，不碰世界）。
 *
 * <h3>为什么单独抽一个类</h3>
 * 控制台存一份、每盏灯抄一份、网络包和 NBT 都要序列化它 ——
 * 三处各写一遍迟早对不齐（少一个字段就是"改了颜色只有一半灯变色"）。
 * 这里只放纯函数，离线自测可以直接 new 出来断言。
 */
public class AlarmConfig {

    /** 转速上限（转/分）。120 rpm = 每秒 2 圈，再快人眼就糊成一片了 */
    public static final int MAX_RPM = 120;

    /** 灯光颜色 0xRRGGBB */
    public int color = 0xFF3A3A;
    /** 转速（转/分），0 = 只亮不转 */
    public int rpm = 20;
    /** 开 / 关 */
    public boolean on;

    public AlarmConfig() {
    }

    public AlarmConfig(int color, int rpm, boolean on) {
        this.color = color & 0xFFFFFF;
        this.rpm = clampRpm(rpm);
        this.on = on;
    }

    public AlarmConfig copy() {
        return new AlarmConfig(this.color, this.rpm, this.on);
    }

    /** 改一份副本（链式用），原对象不动 */
    public AlarmConfig withOn(boolean value) {
        AlarmConfig c = this.copy();
        c.on = value;
        return c;
    }

    public AlarmConfig withColor(int rgb) {
        AlarmConfig c = this.copy();
        c.color = rgb & 0xFFFFFF;
        return c;
    }

    public AlarmConfig withRpm(int value) {
        AlarmConfig c = this.copy();
        c.rpm = clampRpm(value);
        return c;
    }

    public static int clampRpm(int v) {
        if (v < 0) {
            return 0;
        }
        return v > MAX_RPM ? MAX_RPM : v;
    }

    /**
     * 每秒转多少度。{@code rpm} 是"每分钟圈数"，一圈 360 度 ⇒ 每秒 {@code rpm * 6} 度。
     * 渲染里再除以 20 得到"每刻多少度"。
     */
    public double degreesPerSecond() {
        return this.rpm * 6.0D;
    }

    public boolean sameAs(AlarmConfig o) {
        return o != null && o.color == this.color && o.rpm == this.rpm && o.on == this.on;
    }

    // ==================== NBT ====================

    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        c.setInteger("AColor", this.color & 0xFFFFFF);
        c.setInteger("ARpm", this.rpm);
        c.setBoolean("AOn", this.on);
        return c;
    }

    public void readFromNBT(NBTTagCompound c) {
        this.color = c.getInteger("AColor") & 0xFFFFFF;
        this.rpm = clampRpm(c.getInteger("ARpm"));
        this.on = c.getBoolean("AOn");
    }

    /** 人话摘要（GUI 底部 / 控制台提示） */
    public String summary() {
        String hex = String.format(java.util.Locale.ROOT, "#%06X", this.color & 0xFFFFFF);
        return hex + " · " + this.rpm + " rpm" + (this.on ? " · 开" : " · 关");
    }
}
