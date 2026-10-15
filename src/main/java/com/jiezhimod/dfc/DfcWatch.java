package com.jiezhimod.dfc;

import io.netty.buffer.ByteBuf;

/**
 * 自定义显示屏上的**一条数据**（一个数据槽）。
 *
 * 【为什么要单独一个类，而不是塞几个平行数组】
 * 一个槽里有"设备坐标 + 数据种类 + 读数 + 进度条 + 存活标记"五六样东西，
 * 用平行数组（x[] / y[] / kind[] / value[]…）写起来短，但增删一个槽时
 * 六个数组合起来必须同步移位，漏一个就是"删了第 3 项，结果显示的是第 4 项的值"。
 * 一个对象一条记录，增删就是 List.add/remove，不会有这种错位。
 *
 * 【字段分两类】
 *   - **配置**（坐标 / 种类 / 索引）：连接时写一次，存进方块实体 NBT，
 *     服务端重启、区块卸载重载后都还在；
 *   - **读数**（value / ratio / alive）：每 tick 由服务端重新探测设备算出来，
 *     只活在内存与网络包里，不进 NBT —— 存了反而是"重启后先显示一个过期值"。
 *
 * 【不引用任何 Minecraft 类型】坐标拆成三个 int，方便离线自测直接构造。
 */
public final class DfcWatch {

    /** 空槽（还没连设备） */
    public static final int KIND_NONE = 0;
    /** 流体容器：储液罐 / 桶 / 各种 machine tank */
    public static final int KIND_FLUID = 1;
    /** 能量存储：电池 */
    public static final int KIND_ENERGY = 2;
    /**
     * 模块化涡轮部件的**转速**（rps）。
     *
     * 【为什么单独一类，而不是塞进 KIND_NONE 或复用 KIND_ENERGY】
     * 飞轮这类部件自己既没有罐也没有 getPower()，唯一的读数是它所属涡轮核心
     * （{@code MTCoreTE.rps}）的转速。转速不是 0~1 的比例、也不是能量，
     * 归一化方式（{@link DfcFormat#SPIN_FULL_SCALE}）与其它种类都不同，
     * 所以单独给一个 KIND，才能让刷新时知道该走哪条读取分支。
     */
    public static final int KIND_SPIN = 3;
    /**
     * 模块化涡轮部件的**输出功率**（发出来的电）。
     * 与 {@link #KIND_ENERGY} 的区别：KIND_ENERGY 是"存了多少"（有 maxPower 可作满刻度），
     * 这里是"正在发多少"，没有满刻度，只是一个数。
     */
    public static final int KIND_TURBINE_POWER = 4;

    /** 一块自定义屏最多挂多少条数据（需求："最多可以显示 8 个数据"） */
    public static final int MAX = 8;

    // ==================== 配置（进 NBT） ====================
    public int x;
    public int y;
    public int z;
    /** 设备所在维度；与屏幕不同维度时直接判失效 */
    public int dim;
    /** 数据种类，取 KIND_* */
    public int kind = KIND_NONE;
    /**
     * 同一台设备上第几个容器。一台机器可能带好几个罐
     * （例如 {@code TileEntityStorageDrum} 的 {@code tanks[]}），
     * 需要用它区分"这个槽盯的是第 0 个罐还是第 1 个罐"。
     */
    public int index;

    // ==================== 读数（每 tick 重算） ====================
    /** 设备名，如"储液罐" / "电池"（服务端按类名归类，不依赖语言文件） */
    public String device = "";
    /**
     * 数据名。流体是**语言键**（服务端拿不到翻译，见 DfcBridge 的说明），
     * 客户端收到包后翻成中文；能量等固定项直接就是中文。
     */
    public String name = "";
    /** 当前读数文本 */
    public String value = "--";
    /** 0~1 的进度条；小于 0 表示这一项没有"满刻度"概念（不画条） */
    public double ratio = -1.0D;
    /** 设备是否还在。被拆掉之后置 false —— 界面显示"离线"，而不是保留最后一帧的假读数 */
    public boolean alive;

    public DfcWatch() {
    }

    public DfcWatch(int x, int y, int z, int dim, int kind, int index) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.dim = dim;
        this.kind = kind;
        this.index = index;
        this.alive = true;
    }

    /** 同一个数据槽？（坐标 + 种类 + 索引 三者全同） */
    public boolean sameAs(DfcWatch o) {
        return o != null && this.x == o.x && this.y == o.y && this.z == o.z
                && this.dim == o.dim && this.kind == o.kind && this.index == o.index;
    }

    public DfcWatch copy() {
        DfcWatch w = new DfcWatch(this.x, this.y, this.z, this.dim, this.kind, this.index);
        w.device = this.device;
        w.name = this.name;
        w.value = this.value;
        w.ratio = this.ratio;
        w.alive = this.alive;
        return w;
    }

    /** 清空成空槽（保留坐标与种类，方便"设备没了但槽还留着"） */
    public void clearReading() {
        this.value = "--";
        this.ratio = -1.0D;
        this.alive = false;
    }

    /** 人话坐标，给提示消息用 */
    public String posText() {
        return x + " " + y + " " + z;
    }

    // ==================== 网络序列化 ====================

    public void writeTo(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(dim);
        buf.writeByte(kind);
        buf.writeInt(index);
        buf.writeBoolean(alive);
        buf.writeDouble(ratio);
        DfcSnapshot.writeShortString(buf, device);
        DfcSnapshot.writeShortString(buf, name);
        DfcSnapshot.writeShortString(buf, value);
    }

    public void readFrom(ByteBuf buf) {
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        this.dim = buf.readInt();
        this.kind = buf.readByte();
        this.index = buf.readInt();
        this.alive = buf.readBoolean();
        this.ratio = buf.readDouble();
        this.device = DfcSnapshot.readShortString(buf);
        this.name = DfcSnapshot.readShortString(buf);
        this.value = DfcSnapshot.readShortString(buf);
    }
}
