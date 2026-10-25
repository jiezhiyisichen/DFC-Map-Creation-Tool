package com.jiezhimod.dfc;

import io.netty.buffer.ByteBuf;

/**
 * 激光状态显示器上的**一台 DFC 激光设备**（一个槽）。
 *
 * 【为什么要单独一个类】这一页的每台设备要显示 4~5 行「名称 + 数值 + 数据条」，
 * 不同类型的行还不一样（稳定器看透镜、发射器看冷凝胶、喷射器看两种燃料）。
 * 与其在快照里开二十个字段、再在画布上写一大串 if，不如让服务端算好
 * "这一台要显示哪几行"，客户端照单画 —— 也就天然满足"所有数据用数据+数据条"。
 *
 * 【字段分两类】
 *   - 配置/身份：{@link #kind}（决定名字颜色）、{@link #label}（SL1 / PL2 …）；
 *   - 读数：{@link #on}、{@link #metrics}、{@link #warn} / {@link #warnText}。
 *
 * 【不引用任何 Minecraft 类型】和 {@link DfcWatch} 一样，坐标都不需要（槽位本身已经是设备），
 * 所以离线自测可以直接构造它。
 */
public final class LaserInfo {

    // ==================== 类型 ====================
    /** 空槽 */
    public static final int KIND_NONE = 0;
    /** 稳定器 SL：黄色名字 */
    public static final int KIND_SL = 1;
    /** 发射器 PL（能源激光 / 加热）：红色名字 */
    public static final int KIND_PL = 2;
    /** 接收器 OL：浅蓝名字 */
    public static final int KIND_OL = 3;
    /** 燃料喷射器：白色名字 */
    public static final int KIND_INJ = 4;
    /** 其它 DFC 激光部件（HBM / 诅咒版里剩下那些）：按冷却/加热上色 */
    public static final int KIND_GEN = 5;

    // ==================== 警告等级 ====================
    // 冷凝胶与透镜耐久共用这套三级色，和全局的三级语义对齐（0 正常 = 不报警）。
    public static final int WARN_NONE = 0;
    public static final int WARN_YELLOW = 1;
    public static final int WARN_ORANGE = 2;
    public static final int WARN_RED = 3;

    /** 一条读数行在画面上占几行（数值行 / 名称行由画布决定） */
    public static final int MAX_METRICS = 5;

    // ==================== 身份 ====================
    public int kind = KIND_NONE;
    /** 按添加顺序编的短名：SL1、SL2、PL1…（同一类型内递增） */
    public String label = "";
    /** 设备名（方块名 / 类名归类），弹窗与列表里用来区分 */
    public String device = "";
    /** 设备还在不在 */
    public boolean alive;

    // ==================== 读数 ====================
    /** 开启状态：画在激光图上 —— true 就射出光束 */
    public boolean on;
    /** 冷凝胶 / 透镜耐久之类需要预警时填 {@link #WARN_*} */
    public int warn = WARN_NONE;
    /**
     * 弹窗文案（服务端拼好，含具体数字）。
     * 空串 = 这一台没有需要弹窗的事。多条警告时画布按槽位顺序轮流显示。
     */
    public String warnText = "";
    /** 4~5 行「名称 + 数值 + 数据条」，ratio &lt; 0 表示这一行不画条 */
    public final java.util.List<LaserMetric> metrics = new java.util.ArrayList<LaserMetric>();

    public LaserInfo() {
    }

    public LaserInfo(int kind, String label, String device) {
        this.kind = kind;
        this.label = label == null ? "" : label;
        this.device = device == null ? "" : device;
    }

    /** 加一行读数；超过 {@link #MAX_METRICS} 行就丢掉（版面装不下） */
    public LaserInfo metric(String name, String value, double ratio) {
        return metric(name, value, ratio, WARN_NONE);
    }

    /**
     * 加一行读数，并给这一行单独的预警色。
     *
     * 【为什么预警要挂在"行"上而不是整台设备上】需求点名冷凝胶的
     * "黄 / 橙 / 红 要同时用在名字、数据条和数字上" —— 那是**这一行**的属性。
     * 整台设备只留一个 {@link #warn} 做"要不要弹窗"的总闸。
     */
    public LaserInfo metric(String name, String value, double ratio, int warn) {
        if (this.metrics.size() < MAX_METRICS) {
            LaserMetric m = new LaserMetric(name, value, ratio);
            m.warn = Math.max(0, Math.min(WARN_RED, warn));
            this.metrics.add(m);
        }
        return this;
    }

    /** 有没有需要弹窗提醒的事 */
    public boolean hasWarning() {
        return this.warn >= WARN_YELLOW && this.warnText != null && !this.warnText.isEmpty();
    }

    public LaserInfo copy() {
        LaserInfo o = new LaserInfo(this.kind, this.label, this.device);
        o.alive = this.alive;
        o.on = this.on;
        o.warn = this.warn;
        o.warnText = this.warnText;
        for (int i = 0; i < this.metrics.size(); i++) {
            o.metrics.add(this.metrics.get(i).copy());
        }
        return o;
    }

    // ==================== 网络序列化 ====================

    public void writeTo(ByteBuf buf) {
        buf.writeByte(this.kind);
        buf.writeBoolean(this.alive);
        buf.writeBoolean(this.on);
        buf.writeByte(this.warn);
        DfcSnapshot.writeShortString(buf, this.label);
        DfcSnapshot.writeShortString(buf, this.device);
        DfcSnapshot.writeShortString(buf, this.warnText);
        buf.writeByte(this.metrics.size());
        for (int i = 0; i < this.metrics.size(); i++) {
            this.metrics.get(i).writeTo(buf);
        }
    }

    public void readFrom(ByteBuf buf) {
        this.kind = buf.readByte();
        this.alive = buf.readBoolean();
        this.on = buf.readBoolean();
        this.warn = buf.readByte();
        this.label = DfcSnapshot.readShortString(buf);
        this.device = DfcSnapshot.readShortString(buf);
        this.warnText = DfcSnapshot.readShortString(buf);
        this.metrics.clear();
        int n = buf.readByte() & 255;
        for (int i = 0; i < n && i < MAX_METRICS; i++) {
            LaserMetric m = new LaserMetric();
            m.readFrom(buf);
            this.metrics.add(m);
        }
    }

    /** 一行读数：名称 + 数值 + 0~1 的数据条（&lt;0 = 不画条） */
    public static final class LaserMetric {
        public String name = "";
        public String value = "--";
        /** 0~1；小于 0 表示这一项没有"满刻度"概念（只显示数字） */
        public double ratio = -1.0D;
        /** 这一行的预警等级（{@link LaserInfo#WARN_*}）：名字 / 数字 / 数据条一起变色 */
        public int warn = WARN_NONE;

        public LaserMetric() {
        }

        public LaserMetric(String name, String value, double ratio) {
            this.name = name == null ? "" : name;
            this.value = value == null ? "--" : value;
            this.ratio = ratio;
        }

        public LaserMetric copy() {
            LaserMetric m = new LaserMetric(this.name, this.value, this.ratio);
            m.warn = this.warn;
            return m;
        }

        public void writeTo(ByteBuf buf) {
            DfcSnapshot.writeShortString(buf, this.name);
            DfcSnapshot.writeShortString(buf, this.value);
            buf.writeFloat((float) this.ratio);
            buf.writeByte(this.warn);
        }

        public void readFrom(ByteBuf buf) {
            this.name = DfcSnapshot.readShortString(buf);
            this.value = DfcSnapshot.readShortString(buf);
            this.ratio = buf.readFloat();
            this.warn = buf.readByte();
        }
    }
}
