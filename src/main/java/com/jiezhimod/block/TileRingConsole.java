package com.jiezhimod.block;

import com.jiezhimod.ring.RingConsoleConfig;
import com.jiezhimod.ring.RingGroupManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * 灯圈控制台（v4.4）：**接收红石信号，按界面里配置的动作去操作绑定的灯圈组**。
 *
 * <h3>能配什么（全部多选）</h3>
 * 切换颜色至 RGB（直接切换 / 慢慢变换 + 时长）、开启灯圈（普通 / 淡入 + 时长）、
 * 关闭灯圈（普通 / 淡出 + 时长）、闪烁（间隔 + 总时长 + 淡入 / 淡出时长）。
 * 见 {@link RingConsoleConfig}。
 *
 * <h3>控制台之间怎么接力</h3>
 * 每台控制台只管"自己被红石触发的那一发"：
 * 红石信号经中继器延迟后先后触发 A、B 两台控制台，就能做出"淡入到红 → 停两秒 → 淡入到蓝"。
 * 控制台之间互不引用，接力靠的是**灯圈组的状态本身**
 * （{@link RingConsoleConfig#apply} 从当前颜色继续过渡）。
 *
 * <h3>为什么轮询红石而不是监听事件</h3>
 * 和密码控制台同一套结论：1.12.2 红石变化的通知路径太碎，每 5 tick 做一次
 * 上升沿检测，行为确定且便宜（见 {@link TilePasswordConsole}）。
 *
 * <p>绑定用**激光链接器同款手感**：拿 {@link com.jiezhimod.item.ItemRingConnector}
 * 蹲下右键灯圈记录 → 蹲下右键本控制台绑定。</p>
 */
public class TileRingConsole extends TileEntity implements ITickable {

    /** 红石轮询间隔（tick） */
    private static final int SCAN_INTERVAL = 5;
    /** 绑定的灯圈距离上限：超出就当没绑（防止跨维度 / 跨半个地图去控别人的灯） */
    public static final double MAX_BIND_DISTANCE_SQ = 64.0D * 64.0D;

    private BlockPos ringPos;
    private final RingConsoleConfig config = new RingConsoleConfig();

    private boolean lastPowered;
    private int scanTimer;
    /** 上一次触发实际执行了什么（GUI 显示 + 排错） */
    private String lastTriggered = "";

    public TileRingConsole() {
    }

    // ==================== 绑定与配置 ====================

    public BlockPos getRingPos() {
        return this.ringPos;
    }

    /** 绑定 / 换绑（连接器蹲下右键时由服务端调用） */
    public void bindRing(BlockPos pos) {
        this.ringPos = pos;
        this.markDirty();
        this.sync();
    }

    public void clearBinding() {
        this.ringPos = null;
        this.lastTriggered = "";
        this.markDirty();
        this.sync();
    }

    public RingConsoleConfig getConfig() {
        return this.config;
    }

    /** GUI「应用」走这里：整份配置替换（服务端会再钳一遍数值） */
    public void applyConfig(RingConsoleConfig next) {
        if (next == null) {
            return;
        }
        this.config.copyFrom(next);
        this.markDirty();
        this.sync();
    }

    /**
     * 客户端收到整份状态后落地（{@link com.jiezhimod.network.msg.MessageRingConsoleSync}）。
     *
     * <p>只写字段，不触发任何动作 —— 客户端这份纯粹是给 GUI 读取的镜像。</p>
     */
    public void applySync(RingConsoleConfig cfg, BlockPos ring, String last) {
        if (cfg != null) {
            this.config.copyFrom(cfg);
        }
        this.ringPos = ring;
        this.lastTriggered = last == null ? "" : last;
    }

    public String getLastTriggered() {
        return this.lastTriggered;
    }

    // ==================== tick ====================

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        if (++this.scanTimer < SCAN_INTERVAL) {
            return;
        }
        this.scanTimer = 0;
        boolean powered = this.world.isBlockIndirectlyGettingPowered(this.pos) > 0;
        if (powered && !this.lastPowered) {
            this.trigger();
        }
        this.lastPowered = powered;
    }

    /** 红石上升沿：执行配置的动作（多个控制台靠红石延迟接力） */
    private void trigger() {
        if (this.config.isEmpty()) {
            this.lastTriggered = "未选择任何动作";
            com.jiezhimod.JiezhiMod.warn("[RingConsole@" + this.pos + "] triggered but no action selected");
            return;
        }
        if (this.ringPos == null) {
            this.lastTriggered = "未绑定灯圈";
            return;
        }
        TileEntity te = this.world.getTileEntity(this.ringPos);
        if (!(te instanceof TileLightRing)) {
            this.lastTriggered = "绑定的灯圈不存在";
            return;
        }
        String r = RingGroupManager.of(this.world).applyTo(this.world, this.ringPos,
                this.config, "console@" + this.pos.toString());
        this.lastTriggered = r == null ? "执行失败" : r;
        this.sync();   // 让 GUI 的"上次触发"立刻更新
        // 给附近玩家一条可读反馈（离得远的不打扰）
        this.world.playerEntities.stream()
                .filter(o -> o instanceof net.minecraft.entity.player.EntityPlayerMP)
                .map(o -> (net.minecraft.entity.player.EntityPlayerMP) o)
                .filter(p -> p.getDistanceSq(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D,
                        this.pos.getZ() + 0.5D) <= 64.0D)
                .forEach(p -> p.sendMessage(new TextComponentString(
                        TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                                + TextFormatting.GRAY + "灯圈控制台 " + this.pos.toString()
                                + " → " + this.lastTriggered)));
    }

    // ==================== NBT / 同步 ====================

    private static final String K_RING = "RingX";

    @Override
    public void readFromNBT(NBTTagCompound c) {
        super.readFromNBT(c);
        this.ringPos = c.hasKey(K_RING) ? new BlockPos(c.getInteger(K_RING),
                c.getInteger("RingY"), c.getInteger("RingZ")) : null;
        if (c.hasKey("Cfg")) {
            this.config.readFromNBT(c.getCompoundTag("Cfg"));
        }
        this.lastTriggered = c.getString("Last");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        super.writeToNBT(c);
        if (this.ringPos != null) {
            c.setInteger(K_RING, this.ringPos.getX());
            c.setInteger("RingY", this.ringPos.getY());
            c.setInteger("RingZ", this.ringPos.getZ());
        }
        NBTTagCompound cfg = new NBTTagCompound();
        this.config.writeToNBT(cfg);
        c.setTag("Cfg", cfg);
        c.setString("Last", this.lastTriggered == null ? "" : this.lastTriggered);
        return c;
    }

    /** 配置随方块实体同步（GUI 打开时能读到当前值） */
    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound c = super.getUpdateTag();
        NBTTagCompound cfg = new NBTTagCompound();
        this.config.writeToNBT(cfg);
        c.setTag("Cfg", cfg);
        c.setString("Last", this.lastTriggered == null ? "" : this.lastTriggered);
        return c;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 1, this.getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        this.readFromNBT(pkt.getNbtCompound());
    }

    /**
     * 把整份状态推给附近玩家。
     *
     * ★ 不能用 {@code world.notifyBlockUpdate}：那条路只发 {@code SPacketBlockChange}
     * （只带方块状态），方块实体 NBT 运行期没人替你发 —— 于是客户端永远是默认配置，
     * 玩家「应用」完一关界面再打开就全变回去了（v4.4 实机 bug）。
     */
    private void sync() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint point =
                new net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint(
                        this.world.provider.getDimension(),
                        this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D,
                        64.0D);
        com.jiezhimod.network.ModNetwork.CHANNEL.sendToAllAround(
                new com.jiezhimod.network.msg.MessageRingConsoleSync(
                        this.pos, this.config, this.ringPos, this.lastTriggered), point);
    }
}
