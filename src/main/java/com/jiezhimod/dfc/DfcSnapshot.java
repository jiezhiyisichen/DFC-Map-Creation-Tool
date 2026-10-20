package com.jiezhimod.dfc;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * DFC（Dark Fusion Core，暗核聚变堆）的一次数据快照。
 *
 * 【为什么要单独一个快照类】
 * 服务端每 N tick 读一次真正的 TileEntity，客户端只拿到这份扁平副本。
 * 这样有三个好处：
 *   1. 网络包只需按固定顺序读写，客户端不必持有任何 HBM 的类引用；
 *   2. 渲染线程不再碰反射，避免每帧 getMethod 的查找开销；
 *   3. 数据源消失（方块被拆）时客户端还能显示最后一帧 + STALE 标记，
 *      而不是直接把画面清空成"没信号"。
 *
 * 【字段来源】全部来自诅咒版 addon 织入核心的接口
 * {@code com.leafia.overwrite_contents.interfaces.IMixinTileEntityCore}，
 * 以及稳定器 {@code MixinTileEntityCoreStabilizer} 的 public 字段。
 * 具体由 {@link DfcBridge} 负责反射读取。
 */
public final class DfcSnapshot {

    /** 数据源种类：没连上 / 核心 / 稳定器 */
    public static final int SOURCE_NONE = 0;
    public static final int SOURCE_CORE = 1;
    public static final int SOURCE_STABILIZER = 2;

    /** 本次读取是否成功拿到数据源 */
    public boolean linked;
    /** 数据源种类，取 SOURCE_* */
    public int sourceKind;

    // ==================== 核心本体 ====================
    /** 是否有核心（isDFCHasCore）：多方块中心那根"燃料柱" */
    public boolean hasCore;
    /** 反应堆温度，单位 K（getDFCTemperature） */
    public double temperature;
    /** 熔点，单位 K（getDFCMeltingPoint）；温度超过它就开始融毁 */
    public int meltingPoint;
    /** 失效保护等级（getDFCFailsafeLevel） */
    public double failsafe;
    /** 稳定度 0~1（getDFCStabilization）；低于 1 会持续掉完整度 */
    public double stabilization;
    /**
     * 温度变化率（每 tick 的 K 变化）。
     * 诅咒版的 getter 里没有现成字段，这是读取方拿"本次温度 - 上次温度 / 间隔 tick"算出来的，
     * 界面上那个 "+2/s" 就是它 ×20。
     */
    public double tempDelta;

    // ==================== 能量 ====================
    //
    // 【单位：全部都是"原始值"，×1000 才是 SPK】这几个 getter 返回的都不是 HE。
    // 作者自己的 CoreGUI 是 {@code containedEnergy * 1000 + "SPK"}、
    // {@code expellingEnergy / 1000 + "MSPK/s"}（同一把尺子的两种写法）。
    // 界面一律走 {@link DfcFormat#spk}/{@link DfcFormat#spkRate} 换算，
    // **不要直接送进 {@code DfcFormat.energy()}**（那是 HBM 通用机器的 HE 单位）。

    /** 当前蕴含能量（getDFCContainedEnergy），原始值 ×1000 = SPK */
    public double containedEnergy;
    /**
     * getDFCMaxEnergy 的返回值。
     * 【注意】它写死 10000，且在 mod 里从没被调用过（HBM 原版的遗留常量），
     * **不要拿它当进度条的满刻度** —— 满刻度见 {@link DfcFormat#CORE_ENERGY_BAR_SCALE}。
     */
    public double maxEnergy;
    /** 本 tick 吸收的能量（getDFCGainedEnergy，作者代码里就是 containedEnergy 的副本） */
    public double gainedEnergy;
    /** 最近 1 秒放出的能量之和（getDFCExpellingEnergy），原始值 ×1000 = SPK/s */
    public double expellingEnergy;
    /**
     * 潜在增益（getDFCPotentialGain）—— **1 起步的倍率**，不是能量。
     * 作者 GUI 用它驱动指针：{@code angle = min((gain - 1) / 9, client_maxDial) * 360}。
     */
    public double potentialGain;
    /** 输入（getDFCIncomingSpk），与 expellingEnergy 同一把尺子 */
    public double incomingSpk;
    /** 输出（getDFCExpellingSpk），同上 */
    public double expellingSpk;

    // ==================== 运行状态 ====================
    /** 已运行 tick 数（getDFCTicks），界面换算成 [H:MM:SS] */
    public int ticks;
    /** 当前生效的稳定器数量（getDFCStabilizers） */
    public int stabilizers;
    /** 上一 tick 的稳定器数量（getDFCLastStabilizers）—— 两者不等说明结构刚被改动 */
    public int lastStabilizers;
    /** 是否被增幅（isDFCWasBoosted） */
    public boolean boosted;
    /** 是否处于最终阶段（isDFCFinalPhase） */
    public boolean finalPhase;
    /** 坍缩度（getDFCCollapsing）：涨满就是黑洞 */
    public double collapsing;

    // ==================== 危险 ====================
    /** 是否已损毁（isDFCDestroyed） */
    public boolean destroyed;
    /** 距爆炸还有多少 tick（getDFCExplosionIn），-1 表示无倒计时 */
    public double explosionIn;
    /** 过载计时（getDFCOverloadTimer） */
    public int overloadTimer;
    /** 是否进入引爆程序（getDetonation） */
    public boolean detonation;
    /** 引爆倒计时（getDetonationTimer） */
    public int detonationTimer;

    /** 距熔毁还剩多少**秒**（服务端按校准后的单位算好；-1 = 没有倒计时） */
    public float secondsToExplosion = -1.0F;
    /** 距引爆还剩多少**秒**（同上） */
    public float secondsToDetonation = -1.0F;
    /** 检测到的多方块部件数量（getDFCComponentPositions 的长度）—— 反映结构是否完整 */
    public int componentCount;

    // ==================== 与反应堆连接的部件（第二屏） ====================
    /**
     * 【为什么是"合并后的聚合值"】
     * 一台 DFC 反应堆周围可能挂着十几台发射器/接收器/燃料喷射器，
     * 操作员要的是"我现在一共有几台、总共打进/打出多少"，
     * 而不是逐个设备的清单 —— 面板只有 256x192，列清单根本没处放。
     * 所以这里全部是**聚合量**：台数 + 合计读数。
     */
    /** 已连接的发射器台数 */
    public int emitterCount;
    /** 其中正在工作的台数（isOn） */
    public int emitterActiveCount;
    /** 发射器功率档位之和（watts，每台 1~100） */
    public int emitterWatts;
    /** 发射器本 tick 合计打出的 SPK（sum of prev） */
    public double emitterSpk;
    /** 已连接的接收器（吸收器）台数 */
    public int receiverCount;
    /** 接收器合计已接收能量（syncJoules） */
    public double receiverJoules;
    /** 已连接的燃料喷射器台数 */
    public int injectorCount;
    /** 燃料喷射器合计储液量（两台罐之和） */
    public double injectorFuel;

    // ==================== 核心双燃料罐 ====================
    /**
     * 核心自带两个燃料罐（诅咒版 NBT 键 fuel1 / fuel2），由燃料喷射器注入。
     * 液位是 **0~1 的比值**（fill / maxFill），直接送 percent() 即可。
     */
    public String fuelTypeA = "--";
    public double fuelRatioA;
    public String fuelTypeB = "--";
    public double fuelRatioB;

    // ==================== 自定义显示屏的数据槽 ====================
    /**
     * 自定义屏上最多 8 条"盯着的读数"（见 {@link DfcWatch}）。
     *
     * 【为什么放在快照里，而不是单独开一个包】自定义屏与核心屏是同一台硬件、
     * 同一个方块实体、同一条同步通道，差别只在画哪一页。单独发包意味着要再维护
     * 一套"玩家附近有哪些屏、各自发什么"的路由；塞进快照则天然跟着现有的
     * 广播节奏走，也不会出现"核心数据到了、自定义数据还在路上"的错位。
     *
     * 【核心屏的开销是 0】编码时先写条目数，为 0 时后面一个字节都不写，
     * 所以两块核心屏/监视器的包大小与加这个字段之前完全一样。
     */
    public final List<DfcWatch> watches = new ArrayList<DfcWatch>();

    // ==================== 稳定器自身 ====================
    /** 数据源里有没有稳定器信息（绑定的是稳定器时为 true） */
    public boolean stabPresent;
    /** 稳定器是否在运行（isOn） */
    public boolean stabActive;
    /** 稳定器功率等级（watts） */
    public int stabLevel;
    /** 稳定器功率储量（power，HE） */
    public long stabPower;
    /** 透镜类型名（LensType.toString()），无透镜为 "NONE" */
    public String lensType = "NONE";
    /** 透镜耐久百分比 0~100 */
    public float lensHealth;

    public DfcSnapshot() {
        this.lensType = "NONE";
        this.explosionIn = -1.0D;
    }

    /** 清空成"未连接"状态（用于数据源失效、或没装诅咒版 addon 时） */
    public static DfcSnapshot empty() {
        return new DfcSnapshot();
    }

    public static DfcSnapshot copied(DfcSnapshot src) {
        DfcSnapshot s = new DfcSnapshot();
        if (src != null) {
            s.copyFrom(src);
        }
        return s;
    }

    public void copyFrom(DfcSnapshot o) {
        this.linked = o.linked;
        this.sourceKind = o.sourceKind;
        this.hasCore = o.hasCore;
        this.temperature = o.temperature;
        this.meltingPoint = o.meltingPoint;
        this.failsafe = o.failsafe;
        this.stabilization = o.stabilization;
        this.tempDelta = o.tempDelta;
        this.containedEnergy = o.containedEnergy;
        this.maxEnergy = o.maxEnergy;
        this.gainedEnergy = o.gainedEnergy;
        this.expellingEnergy = o.expellingEnergy;
        this.potentialGain = o.potentialGain;
        this.incomingSpk = o.incomingSpk;
        this.expellingSpk = o.expellingSpk;
        this.ticks = o.ticks;
        this.stabilizers = o.stabilizers;
        this.lastStabilizers = o.lastStabilizers;
        this.boosted = o.boosted;
        this.finalPhase = o.finalPhase;
        this.collapsing = o.collapsing;
        this.destroyed = o.destroyed;
        this.explosionIn = o.explosionIn;
        this.overloadTimer = o.overloadTimer;
        this.detonation = o.detonation;
        this.detonationTimer = o.detonationTimer;
        this.secondsToExplosion = o.secondsToExplosion;
        this.secondsToDetonation = o.secondsToDetonation;
        this.componentCount = o.componentCount;
        this.emitterCount = o.emitterCount;
        this.emitterActiveCount = o.emitterActiveCount;
        this.emitterWatts = o.emitterWatts;
        this.emitterSpk = o.emitterSpk;
        this.receiverCount = o.receiverCount;
        this.receiverJoules = o.receiverJoules;
        this.injectorCount = o.injectorCount;
        this.injectorFuel = o.injectorFuel;
        this.fuelTypeA = o.fuelTypeA == null ? "--" : o.fuelTypeA;
        this.fuelRatioA = o.fuelRatioA;
        this.fuelTypeB = o.fuelTypeB == null ? "--" : o.fuelTypeB;
        this.fuelRatioB = o.fuelRatioB;
        this.stabPresent = o.stabPresent;
        this.stabActive = o.stabActive;
        this.stabLevel = o.stabLevel;
        this.stabPower = o.stabPower;
        this.lensType = o.lensType == null ? "NONE" : o.lensType;
        this.lensHealth = o.lensHealth;
        // 数据槽是可变对象列表，必须**深拷贝**：浅拷会让两块屏共享同一批 DfcWatch，
        // 服务端给 A 屏刷新读数时顺手把 B 屏的也改了（离线自测有专门一条断言钉住）。
        this.watches.clear();
        for (int i = 0; i < o.watches.size(); i++) {
            this.watches.add(o.watches.get(i).copy());
        }
    }

    // ==================== 网络序列化 ====================

    /**
     * 按固定顺序写入。字段一多最容易出的 bug 就是读写的**顺序**不一致，
     * 所以读写两个方法必须紧挨着放在一起看（下面的 readFrom 与它镜像对称）。
     */
    public void writeTo(ByteBuf buf) {
        buf.writeBoolean(linked);
        buf.writeByte(sourceKind);
        buf.writeBoolean(hasCore);
        buf.writeDouble(temperature);
        buf.writeInt(meltingPoint);
        buf.writeDouble(failsafe);
        buf.writeDouble(stabilization);
        buf.writeDouble(tempDelta);
        buf.writeDouble(containedEnergy);
        buf.writeDouble(maxEnergy);
        buf.writeDouble(gainedEnergy);
        buf.writeDouble(expellingEnergy);
        buf.writeDouble(potentialGain);
        buf.writeDouble(incomingSpk);
        buf.writeDouble(expellingSpk);
        buf.writeInt(ticks);
        buf.writeInt(stabilizers);
        buf.writeInt(lastStabilizers);
        buf.writeBoolean(boosted);
        buf.writeBoolean(finalPhase);
        buf.writeDouble(collapsing);
        buf.writeBoolean(destroyed);
        buf.writeDouble(explosionIn);
        buf.writeInt(overloadTimer);
        buf.writeBoolean(detonation);
        buf.writeInt(detonationTimer);
        buf.writeFloat(secondsToExplosion);
        buf.writeFloat(secondsToDetonation);
        buf.writeInt(componentCount);
        // ---- 部件聚合（第二屏） ----
        buf.writeInt(emitterCount);
        buf.writeInt(emitterActiveCount);
        buf.writeInt(emitterWatts);
        buf.writeDouble(emitterSpk);
        buf.writeInt(receiverCount);
        buf.writeDouble(receiverJoules);
        buf.writeInt(injectorCount);
        buf.writeDouble(injectorFuel);
        writeShortString(buf, fuelTypeA);
        buf.writeDouble(fuelRatioA);
        writeShortString(buf, fuelTypeB);
        buf.writeDouble(fuelRatioB);
        buf.writeBoolean(stabPresent);
        buf.writeBoolean(stabActive);
        buf.writeInt(stabLevel);
        buf.writeLong(stabPower);
        buf.writeFloat(lensHealth);
        // 字符串用 UTF-8 短串（透镜类型名最长 "STANDARD"），比 writeUTF 省 2 字节
        writeShortString(buf, lensType);
        // ---- 自定义屏数据槽（核心屏此处 count = 0，一个字节都不额外发） ----
        buf.writeByte(watches.size());
        for (int i = 0; i < watches.size(); i++) {
            watches.get(i).writeTo(buf);
        }
    }

    /**
     * UTF-8 短串：1 字节长度 + 内容（最长 255 字节）。读写必须成对使用。
     * 包级可见：{@link DfcWatch} 也用它，避免两处各写一份长度前缀逻辑。
     */
    static void writeShortString(ByteBuf buf, String s) {
        byte[] b = s == null ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(b.length, 255);
        buf.writeByte(n);
        buf.writeBytes(b, 0, n);
    }

    static String readShortString(ByteBuf buf) {
        int n = buf.readByte() & 255;
        if (n == 0) {
            return "";
        }
        byte[] b = new byte[n];
        buf.readBytes(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    public void readFrom(ByteBuf buf) {
        this.linked = buf.readBoolean();
        this.sourceKind = buf.readByte();
        this.hasCore = buf.readBoolean();
        this.temperature = buf.readDouble();
        this.meltingPoint = buf.readInt();
        this.failsafe = buf.readDouble();
        this.stabilization = buf.readDouble();
        this.tempDelta = buf.readDouble();
        this.containedEnergy = buf.readDouble();
        this.maxEnergy = buf.readDouble();
        this.gainedEnergy = buf.readDouble();
        this.expellingEnergy = buf.readDouble();
        this.potentialGain = buf.readDouble();
        this.incomingSpk = buf.readDouble();
        this.expellingSpk = buf.readDouble();
        this.ticks = buf.readInt();
        this.stabilizers = buf.readInt();
        this.lastStabilizers = buf.readInt();
        this.boosted = buf.readBoolean();
        this.finalPhase = buf.readBoolean();
        this.collapsing = buf.readDouble();
        this.destroyed = buf.readBoolean();
        this.explosionIn = buf.readDouble();
        this.overloadTimer = buf.readInt();
        this.detonation = buf.readBoolean();
        this.detonationTimer = buf.readInt();
        this.secondsToExplosion = buf.readFloat();
        this.secondsToDetonation = buf.readFloat();
        this.componentCount = buf.readInt();
        // ---- 部件聚合（第二屏），顺序必须与 writeTo 严格一致 ----
        this.emitterCount = buf.readInt();
        this.emitterActiveCount = buf.readInt();
        this.emitterWatts = buf.readInt();
        this.emitterSpk = buf.readDouble();
        this.receiverCount = buf.readInt();
        this.receiverJoules = buf.readDouble();
        this.injectorCount = buf.readInt();
        this.injectorFuel = buf.readDouble();
        this.fuelTypeA = readShortString(buf);
        this.fuelRatioA = buf.readDouble();
        this.fuelTypeB = readShortString(buf);
        this.fuelRatioB = buf.readDouble();
        this.stabPresent = buf.readBoolean();
        this.stabActive = buf.readBoolean();
        this.stabLevel = buf.readInt();
        this.stabPower = buf.readLong();
        this.lensHealth = buf.readFloat();
        String lens = readShortString(buf);
        this.lensType = lens.isEmpty() ? "NONE" : lens;
        // ---- 自定义屏数据槽，顺序必须与 writeTo 严格一致 ----
        this.watches.clear();
        int watchCount = buf.readByte() & 255;
        for (int i = 0; i < watchCount; i++) {
            DfcWatch w = new DfcWatch();
            w.readFrom(buf);
            this.watches.add(w);
        }
    }
}
