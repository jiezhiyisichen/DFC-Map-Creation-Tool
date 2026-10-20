package com.jiezhimod.block;

import com.jiezhimod.dfc.DfcBridge;
import com.jiezhimod.dfc.DfcSnapshot;
import com.jiezhimod.dfc.ScreenLayout;
import com.jiezhimod.dfc.ScreenStructure;
import com.jiezhimod.client.dfc.ScreenCanvas;
import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageDfcScreen;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * DFC 显示器的主方块实体（只挂在 part 0 那一格）。
 *
 * 【服务端权威】数据的读取与事件日志都在服务端算，客户端只拿结果渲染。
 * 这样多台显示器看到的日志顺序一致，也让客户端完全不碰反射。
 *
 * 【刷新节奏】每 {@link #REFRESH_INTERVAL} tick（0.2 秒）读一次并广播。
 * DFC 的温度是每 tick 都在动的连续量，逐 tick 同步既没必要也浪费带宽；
 * 但 0.5 秒（原来的 10 tick）会让指针明显"跳字"，0.2 秒既跟得上读数、
 * 又比逐 tick 省 80% 的包量。
 *
 * 【数据源失效】绑定坐标上的方块被拆掉、或整合包里没装诅咒版 addon 时，
 * DfcBridge 会返回"未连接"快照 —— 界面显示 未连接，而不是崩溃或空白。
 */
public class TileDfcScreen extends TileEntity implements ITickable {

    /**
     * 数据刷新间隔（tick）。取值来自 {@link ScreenLayout#REFRESH_TICKS}（4 tick = 200ms），
     * 与客户端画布的重绘周期共用同一个常量，保证读数和画面同一个节拍。
     *
     * 【为什么要和画布对齐】温度变化率 {@code tempDelta} 是用
     * (cur - prev) / REFRESH_INTERVAL 折算的，改这里会同时改变变化率的量纲；
     * 而画布若用另一个周期，就会出现"数值已经变了但画面还是上一帧"的错位感。
     */
    public static final int REFRESH_INTERVAL = ScreenLayout.REFRESH_TICKS;
    /** 全量同步的广播半径 */
    private static final double SYNC_RANGE = 96.0D;

    // ==================== 服务端状态 ====================
    private boolean powered;
    private int tilt = ScreenStructure.TILT_DEFAULT;
    /** 玩家用遥控器绑定的坐标（核心或稳定器） */
    private BlockPos sourcePos;
    /** 解析出来的核心坐标（绑定稳定器时会指向它挂的核心），用于只读一次扫描 */
    private BlockPos corePos;

    private final DfcSnapshot data = new DfcSnapshot();
    private final DfcSnapshot prev = new DfcSnapshot();
    private final List<String> events = new ArrayList<String>();

    private int refreshTimer;
    private boolean forceSync = true;

    /**
     * 这台屏自己的刷新间隔（tick）。
     *
     * 【为什么从常量改成每台一个】四块屏同时开着时，每块每帧都要重画 768×576 画布
     * 再整张上传纹理，玩家实测只有 9 FPS。默认仍是 {@link ScreenLayout#REFRESH_TICKS}
     * （1 tick = 50ms），但可以用**调试棒**按设备调慢：部件页 100ms、自定义屏 200ms 就够看，
     * 开销立刻降到 1/2 ~ 1/4。
     *
     * 注意它同时是 {@code tempDelta} 的除数与 {@code runTicks} 的增量 ——
     * 改刷新率时这两个量纲必须跟着走，否则温度变化率会凭空放大或缩小。
     */
    private int intervalTicks = ScreenLayout.REFRESH_TICKS;

    /** 可选的刷新率（毫秒）。调试界面按这个表出按钮，服务端也按它校验 */
    public static final int[] REFRESH_CHOICES_MS = {50, 100, 200, 500, 1000};

    public int getIntervalTicks() {
        return this.intervalTicks;
    }

    /**
     * 客户端用：把服务端广播过来的刷新率写进本地镜像。
     * 不 markDirty、不回推 —— 客户端那份永远只是影子。
     */
    public void setClientRefreshMs(int ms) {
        this.intervalTicks = Math.max(1, ms / 50);
    }

    /** 毫秒形式的刷新周期（客户端画布按它算重绘节拍） */
    public int getRefreshMs() {
        return this.intervalTicks * 50;
    }

    /**
     * 设置刷新率。只接受 {@link #REFRESH_CHOICES_MS} 里的值 ——
     * 白名单比 clamp 好：把 37ms 这种值静默改成 50ms，玩家会以为"调了没反应"。
     */
    public boolean setRefreshMs(int ms) {
        int ticks = 0;
        for (int v : REFRESH_CHOICES_MS) {
            if (v == ms) {
                ticks = Math.max(1, v / 50);
                break;
            }
        }
        if (ticks <= 0 || ticks == this.intervalTicks) {
            return false;
        }
        this.intervalTicks = ticks;
        this.refreshTimer = 0;
        this.forceSync = true;
        markDirty();
        return true;
    }

    /**
     * 界面上的"运行时长"。
     *
     * 【为什么不用核心自己的 getDFCTicks】那是核心从出生起累积的 tick，**不会因为停堆归零**；
     * 而需求要的是"这一炉烧了多久" —— 温度归零（停机）就该清零重新计时。
     * 所以这里自己数：只要读数源是热的（温度 > 0）就 +{@link #REFRESH_INTERVAL}，
     * 一旦温度掉回 0 立刻归零。存进 NBT，区块卸载重载后不会从 0 重来。
     */
    private int runTicks;

    // ==================== 客户端状态 ====================
    /** 客户端：本次开机的起始毫秒（渲染时间轴用） */
    private long clientBootMs;

    /**
     * 客户端：熔毁报错页的开始时刻（0 = 当前没有报错页）。
     *
     * 报错页由 {@link #clientFaultElapsedMs()} 推进：显示满 {@link ScreenLayout#FAULT_MS}
     * 之后自动清掉并**重启屏幕**（把 {@link #clientBootMs} 重新按到现在，重走一遍开机自检）。
     */
    private long clientFaultMs;

    /**
     * 客户端：上一次收到的坍缩度。
     *
     * 熔毁的判据是它**从 0 跳到 &gt;0 的那一帧**（作者代码 {@code collapsing = 1 - explosionIn/120}），
     * 而不是"坍缩度大于某个阈值" —— 后者在核心开始炸之后每一帧都成立，
     * 屏幕就会 4 秒一次没完没了地重启。
     */
    private double clientPrevCollapsing;

    // ==================== 生命周期 ====================

    @Override
    public void update() {
        if (this.world == null) {
            return;
        }
        if (this.world.isRemote) {
            return;   // 客户端只负责渲染，数据全靠服务端推
        }
        this.refreshTimer++;
        if (this.forceSync || this.refreshTimer % this.intervalTicks == 0) {
            this.refresh();
        }
    }

    /** 读一次数据、算增量、产出日志，然后广播给周围玩家 */
    public void refresh() {
        this.forceSync = false;
        this.refreshTimer = 0;

        this.prev.copyFrom(this.data);

        // 核心数据优先从解析出来的 corePos 读；sourcePos 若是稳定器，还要读它自身的字段
        DfcSnapshot src = null;
        if (this.corePos != null) {
            src = DfcBridge.read(this.world, this.corePos);
        }
        if (this.sourcePos != null && (this.corePos == null || !this.sourcePos.equals(this.corePos))) {
            DfcSnapshot st = DfcBridge.read(this.world, this.sourcePos);
            if (src == null || !src.linked) {
                src = st;
            } else if (st.stabPresent) {
                // 两个都要：核心数据 + 稳定器自身
                src.stabPresent = st.stabPresent;
                src.stabActive = st.stabActive;
                src.stabLevel = st.stabLevel;
                src.stabPower = st.stabPower;
                src.lensType = st.lensType;
                src.lensHealth = st.lensHealth;
            }
        }
        this.data.copyFrom(src == null ? DfcSnapshot.empty() : src);

        // 温度变化率：getter 里没有现成字段，用前后两帧折算
        this.data.tempDelta = ScreenLayout.temperatureRate(
                this.prev.temperature, this.data.temperature, this.intervalTicks);

        // 运行时长：温度归零 = 停机，时长清零重新计（需求原话"运行时长应该是在温度归零后清零"）
        if (this.data.linked && this.data.temperature > 0.0D) {
            this.runTicks += this.intervalTicks;
        } else {
            this.runTicks = 0;
        }
        this.data.ticks = this.runTicks;

        // 结构自检：12 格必须都是本方块且 part 对得上
        if (this.data.componentCount == 0 && this.powered) {
            this.data.componentCount = this.countOwnParts();
        }

        appendEvents(ScreenLayout.pendingEvents(this.prev, this.data));

        // 子类补充自己的数据（自定义屏在这里把 8 个数据槽的读数刷一遍）
        fillExtra(this.data);

        this.syncToClients();
    }

    /**
     * 广播之前的扩展点：子类可以往快照里补自己的内容。
     *
     * 【为什么做成钩子而不是把自定义屏整段重写】{@link TileCustomScreen} 需要走的流程
     * （prev 拷贝、温度折算、运行时长、事件日志、同步）与核心屏一模一样，
     * 只有"最后往包里多塞一点东西"不同。整段重写迟早会漏掉某一步。
     */
    protected void fillExtra(DfcSnapshot d) {
        // 核心屏没有额外内容
    }

    private void appendEvents(List<String> news) {
        if (news == null || news.isEmpty()) {
            return;
        }
        for (String s : news) {
            // 同一句话不重复堆叠
            if (!this.events.isEmpty() && this.events.get(this.events.size() - 1).equals(s)) {
                continue;
            }
            this.events.add(s);
        }
        while (this.events.size() > ScreenLayout.LOG_CAPACITY) {
            this.events.remove(0);
        }
    }

    /** 数一下自己这个结构还有几格在（拆掉部分格子时用来提示结构不完整） */
    private int countOwnParts() {
        IBlockState state = this.world.getBlockState(this.pos);
        if (!(state.getBlock() instanceof BlockDfcScreen)) {
            return 0;
        }
        return ScreenMultiblock.countParts(this.world, this.pos, state.getValue(BlockDfcScreen.FACING));
    }

    // ==================== 交互 ====================

    /** 右键开 / 关。开机时清空旧日志，让每次开机都是干净的一次启动过程 */
    public void togglePower(@Nullable EntityPlayer player) {
        this.powered = !this.powered;
        if (this.powered) {
            this.events.clear();
            this.prev.copyFrom(this.data);
        }
        this.forceSync = true;
        this.markDirty();
        if (player != null) {
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + "Display " + (this.powered ? "ON" : "OFF")), true);
        }
        // 立刻刷一次，避免开关延迟一个刷新周期
        this.refresh();
    }

    /** 服务端设置开机（不播提示消息）。放置流程用它让新屏一装好就是亮的 */
    public void powerOn() {
        this.powered = true;
        this.events.clear();
        this.prev.copyFrom(DfcSnapshot.empty());
        this.forceSync = true;
        this.markDirty();
        this.refresh();
    }

    /**
     * 绑定数据源（遥控器调用）。
     * 这里会做一次"重"解析：如果玩家绑的是稳定器，需要扫描找到它挂的核心，
     * 结果缓存进 corePos，之后刷新就只读不扫了。
     */
    public void bindSource(BlockPos src, EntityPlayer player) {
        this.sourcePos = src == null ? null : src.toImmutable();
        this.corePos = src == null ? null : DfcBridge.resolveCore(this.world, src);
        this.events.clear();
        this.prev.copyFrom(DfcSnapshot.empty());
        this.forceSync = true;
        this.markDirty();

        if (player != null) {
            String msg;
            if (src == null) {
                msg = TextFormatting.YELLOW + "Data source cleared.";
            } else if (this.corePos == null) {
                msg = TextFormatting.RED + "No DFC core found at or near "
                        + src.getX() + " " + src.getY() + " " + src.getZ() + ".";
            } else {
                msg = TextFormatting.GREEN + "Bound to DFC core @ "
                        + this.corePos.getX() + " " + this.corePos.getY() + " " + this.corePos.getZ() + ".";
            }
            player.sendStatusMessage(new TextComponentString(TextFormatting.AQUA + "[jiezhimod] "
                    + TextFormatting.RESET + msg), false);
        }
        this.refresh();
    }

    public void setTilt(int t) {
        this.tilt = Math.max(0, Math.min(ScreenStructure.TILT_STEPS - 1, t));
        this.markDirty();
    }

    // ==================== 同步 ====================

    private void syncToClients() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        ModNetwork.CHANNEL.sendToAllAround(new MessageDfcScreen(this),
                new NetworkRegistry.TargetPoint(this.world.provider.getDimension(),
                        this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D, SYNC_RANGE));
    }

    /**
     * 客户端收到包后调用。**三种"重新上电"都从这里重启开机时间轴**：
     *   1. 开机瞬间（powered 由 false 变 true）；
     *   2. **重新加载存档** —— 客户端的方块实体是随区块一起重建的（重进存档 / 换维度 /
     *      F3+A 重载区块），新实例的 {@code clientBootMs} 还是 0，说明这台屏"刚上电"，
     *      于是重新走一遍 节枝OS -> 自检 -> 主界面。
     *      需求原话："在每次存档重新加载时重启所有屏幕"；
     *   3. 熔毁报错页结束（见 {@link #clientFaultElapsedMs()}，那里会再按一次时间轴）。
     */
    public void applyServerState(boolean powered, int tilt, DfcSnapshot snap, List<String> ev) {
        if (powered && (!this.powered || this.clientBootMs <= 0L)) {
            this.clientBootMs = System.currentTimeMillis();
        }
        this.powered = powered;
        this.tilt = tilt;
        this.data.copyFrom(snap);
        // 数据槽里的流体名是**服务端拿到的语言键**（"fluid.diesel"），
        // 只有在客户端才翻得动。这是纯客户端动作，服务端走到这里 isRemote 为 false 直接跳过。
        if (this.world != null && this.world.isRemote) {
            com.jiezhimod.client.dfc.WatchNames.localize(this.data.watches);
        }
        this.events.clear();
        if (ev != null) {
            this.events.addAll(ev);
        }

        // 【熔毁】坍缩度从 0 变成 >0 的那一瞬 = 核心开始炸 -> 整屏改画报错 301。
        // 只有开着的屏才报错（关着的屏本来就什么都没显示，没必要先给它攒一条故障）。
        if (powered && snap != null) {
            if (snap.collapsing > 0.0D && this.clientPrevCollapsing <= 0.0D) {
                this.clientFaultMs = System.currentTimeMillis();
            }
            this.clientPrevCollapsing = snap.collapsing;
        }
    }

    /** 客户端启动时间轴（从开机那一刻算起） */
    public long clientBootElapsedMs() {
        if (this.clientBootMs <= 0L) {
            // 兜底：一次同步包都还没收到。方块实体一载入服务端就会 forceSync，
            // 正常玩不会走到这里；真走到了就直接显示主界面，总比卡在开机画面上好。
            return Long.MAX_VALUE;
        }
        return System.currentTimeMillis() - this.clientBootMs;
    }

    /**
     * 客户端每帧调一次：往前推"报错页 -&gt; 自动重启"这段状态机。
     *
     * @return 报错页已显示的毫秒数；**&lt; 0 表示这一帧不该画报错页**。
     *
     * 显示满 {@link ScreenLayout#FAULT_MS}（4 秒）就把屏幕重启 ——
     * 把开机时间轴重新按到现在，于是又走一遍 节枝OS -> 自检 -> 主界面，
     * 而不是"报错页消失、主界面凭空跳出来"。需求原话："等待4秒后，重启屏幕"。
     */
    public long clientFaultElapsedMs() {
        if (this.clientFaultMs <= 0L) {
            return -1L;
        }
        long elapsed = System.currentTimeMillis() - this.clientFaultMs;
        if (elapsed < ScreenLayout.FAULT_MS) {
            return elapsed;
        }
        this.clientFaultMs = 0L;
        this.clientBootMs = System.currentTimeMillis();
        return -1L;
    }

    // ==================== NBT ====================

    @Override
    public void readFromNBT(NBTTagCompound c) {
        super.readFromNBT(c);
        this.powered = c.getBoolean("Powered");
        this.tilt = c.hasKey("Tilt") ? c.getInteger("Tilt") : ScreenStructure.TILT_DEFAULT;
        this.sourcePos = readPos(c, "Src");
        this.corePos = readPos(c, "Core");
        this.runTicks = c.getInteger("RunTicks");
        // 刷新率要落盘：区块重载后不能自己跳回 50ms，否则玩家调慢的屏一走动就白调了
        this.intervalTicks = c.hasKey("RefreshMs")
                ? Math.max(1, c.getInteger("RefreshMs") / 50)
                : ScreenLayout.REFRESH_TICKS;
        this.forceSync = true;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        super.writeToNBT(c);
        c.setBoolean("Powered", this.powered);
        c.setInteger("Tilt", this.tilt);
        writePos(c, "Src", this.sourcePos);
        writePos(c, "Core", this.corePos);
        c.setInteger("RunTicks", this.runTicks);
        c.setInteger("RefreshMs", this.intervalTicks * 50);
        return c;
    }

    private static BlockPos readPos(NBTTagCompound c, String key) {
        if (!c.hasKey(key + "X")) {
            return null;
        }
        return new BlockPos(c.getInteger(key + "X"), c.getInteger(key + "Y"), c.getInteger(key + "Z"));
    }

    private static void writePos(NBTTagCompound c, String key, BlockPos p) {
        if (p == null) {
            return;
        }
        c.setInteger(key + "X", p.getX());
        c.setInteger(key + "Y", p.getY());
        c.setInteger(key + "Z", p.getZ());
    }

    // ==================== 访问器 ====================

    public boolean isPowered() {
        return this.powered;
    }

    public int getTilt() {
        return this.tilt;
    }

    @Nullable
    public BlockPos getSourcePos() {
        return this.sourcePos;
    }

    @Nullable
    public BlockPos getCorePos() {
        return this.corePos;
    }

    public DfcSnapshot getData() {
        return this.data;
    }

    public List<String> getEvents() {
        return this.events;
    }

    /** 方块被换掉时不该复用旧 TE */
    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return this.writeToNBT(new NBTTagCompound());
    }

    /**
     * TESR 的视锥剔除边界。
     *
     * 默认只给 1 格，而我们的面板跨 4x3 格、倾斜后下边缘还会往前伸出 1 格多，
     * 用小边界会出现"屏幕转到某个角度整块消失"。这里直接放宽到结构外 4 格。
     */
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(this.pos).grow(4.0D).expand(0.0D, 1.0D, 0.0D);
    }

    // ==================== 客户端渲染缓存 ====================

    /**
     * 画面画布。存成 Object 是刻意的：字段类型是客户端类，
     * 声明成具体类型会让服务端在反序列化 TE 时多一次类解析，
     * 而这块东西服务端根本用不到 —— 只有 TESR 会调用 canvas()。
     */
    private Object canvasHolder;

    @SideOnly(Side.CLIENT)
    public ScreenCanvas canvas() {
        if (this.canvasHolder == null) {
            ScreenCanvas c = new ScreenCanvas();
            // 每块屏幕一张自己的纹理。键里带 identityHashCode 是为了万无一失：
            // 同坐标在不同维度、或方块被快速拆装的瞬间，都可能出现短暂的重复坐标。
            c.setLocation(new net.minecraft.util.ResourceLocation("jiezhimod",
                    "dfc_" + this.pos.getX() + "_" + this.pos.getY() + "_" + this.pos.getZ()
                            + "_" + Integer.toHexString(System.identityHashCode(this))));
            this.canvasHolder = c;
        }
        return (ScreenCanvas) this.canvasHolder;
    }

    /**
     * 方块实体失效时才释放 GL 纹理。不做这一步的话，每摆一块屏幕就漏一张
     * 512x384 的显存贴图（拆了重摆几次就很可观）。
     */
    @Override
    public void invalidate() {
        super.invalidate();
        if (this.world != null && this.world.isRemote && this.canvasHolder instanceof ScreenCanvas) {
            ((ScreenCanvas) this.canvasHolder).dispose();
            this.canvasHolder = null;
        }
    }
}
