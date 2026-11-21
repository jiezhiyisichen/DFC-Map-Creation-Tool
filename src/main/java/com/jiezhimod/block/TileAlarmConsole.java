package com.jiezhimod.block;

import com.jiezhimod.alarm.AlarmConfig;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 旋转警示灯控制台（v4.5）：记一份"颜色 + 转速"，收到红石上升沿就把配置推给所有绑定的灯。
 *
 * <h3>为什么控制台保存成员表，而不是让灯去反查控制台</h3>
 * 灯圈系统里"控制台只记一格锚点、触发时按邻居关系现算组"是因为灯圈**必然连成一片**；
 * 警示灯可以东一个西一个，没有"组"这回事 —— 只能老老实实记一份坐标表。
 * 上限 {@link #MAX_MEMBERS} 是防止有人把整张地图的灯都绑到一台控制台上，
 * 那样一次上升沿要改上千个方块实体。
 *
 * <h3>为什么红石在这里判，而不是在方块里</h3>
 * 灯圈那边判红石要靠 {@code neighborChanged}，因为灯自己也要能吃红石。
 * 控制台只有一个入口，用 {@link #update()} 每刻读一次电平就够，
 * 而且上升沿 / 下降沿各处理一次比"邻居变化时乱猜"稳。
 */
public class TileAlarmConsole extends TileEntity implements ITickable {

    /** 一台控制台最多管多少盏灯 */
    public static final int MAX_MEMBERS = 64;

    private final AlarmConfig cfg = new AlarmConfig(0xFF3A3A, 20, false);
    private final List<BlockPos> members = new ArrayList<BlockPos>();

    private boolean lastRedstone;
    private boolean redstoneSeen;

    public AlarmConfig config() {
        return this.cfg;
    }

    public List<BlockPos> members() {
        return this.members;
    }

    /**
     * 写入配置（GUI 保存 / 服务端包）并**立刻**应用到已绑定的灯。
     * 玩家在界面里调一下转速就希望马上看到变化，不该等到下一次红石信号。
     */
    public void setConfig(AlarmConfig next) {
        if (next == null) {
            return;
        }
        this.cfg.color = next.color & 0xFFFFFF;
        this.cfg.rpm = AlarmConfig.clampRpm(next.rpm);
        this.cfg.on = next.on;
        this.markDirty();
        if (this.world != null && !this.world.isRemote) {
            this.applyToMembers(this.cfg.on);
        }
    }

    /** 把 (x,y,z) 加进成员表；已存在就刷新，满了返回 false */
    public boolean addMember(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        for (BlockPos p : this.members) {
            if (p.equals(pos)) {
                this.markDirty();
                return true;
            }
        }
        if (this.members.size() >= MAX_MEMBERS) {
            return false;
        }
        this.members.add(pos.toImmutable());
        this.markDirty();
        return true;
    }

    /** 从成员表里移除（灯被拆时用） */
    public void removeMember(BlockPos pos) {
        if (this.members.remove(pos)) {
            this.markDirty();
        }
    }

    /** 立即把当前配置推给所有成员 */
    public void pushConfig() {
        if (this.world != null && !this.world.isRemote) {
            this.applyToMembers(this.cfg.on);
        }
    }

    private void applyToMembers(boolean on) {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        AlarmConfig snapshot = this.cfg.withOn(on);
        for (BlockPos p : new ArrayList<BlockPos>(this.members)) {
            TileEntity te = this.world.getTileEntity(p);
            if (te instanceof TileAlarmLight) {
                // ★ 走 applyConsoleConfig 而不是 applyConfig：后者会把联动到显示屏的灯
                //   也刷成统一色，告警颜色刚亮就被推回去（见 TileAlarmLight.isAuto）
                ((TileAlarmLight) te).applyConsoleConfig(snapshot);
            }
        }
    }

    // ==================== 红石 ====================

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        boolean powered = this.world.isBlockIndirectlyGettingPowered(this.pos) > 0;
        if (!this.redstoneSeen) {
            // 加载后的第一次读数不算边沿，否则一读档所有灯就"闪一下"
            this.redstoneSeen = true;
            this.lastRedstone = powered;
            return;
        }
        if (powered == this.lastRedstone) {
            return;
        }
        this.lastRedstone = powered;
        this.applyToMembers(powered);
        // 服务端权威：开关状态跟着红石走，写回自己的配置，GUI 打开时看到的就是对的
        this.cfg.on = powered;
        this.markDirty();
        this.syncToClient();
    }

    /** 让客户端（GUI / 别的玩家）拿到最新配置 */
    public void syncToClient() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        net.minecraft.block.state.IBlockState state = this.world.getBlockState(this.pos);
        this.world.notifyBlockUpdate(this.pos, state, state, 3);
        com.jiezhimod.network.ModNetwork.CHANNEL.sendToAllAround(
                new com.jiezhimod.network.msg.MessageAlarmConsole(this.pos, this.cfg, false),
                new net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint(
                        this.world.provider.getDimension(), this.pos.getX(), this.pos.getY(),
                        this.pos.getZ(), 64.0D));
    }

    // ==================== NBT ====================

    @Override
    public void readFromNBT(NBTTagCompound c) {
        super.readFromNBT(c);
        this.cfg.readFromNBT(c);
        this.members.clear();
        NBTTagList list = c.getTagList("AlarmMembers", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            this.members.add(new BlockPos(t.getInteger("X"), t.getInteger("Y"), t.getInteger("Z")));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        super.writeToNBT(c);
        this.cfg.writeToNBT(c);
        NBTTagList list = new NBTTagList();
        for (BlockPos p : this.members) {
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("X", p.getX());
            t.setInteger("Y", p.getY());
            t.setInteger("Z", p.getZ());
            list.appendTag(t);
        }
        c.setTag("AlarmMembers", list);
        return c;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound c = super.getUpdateTag();
        this.cfg.writeToNBT(c);
        return c;
    }

    @Override
    public net.minecraft.network.play.server.SPacketUpdateTileEntity getUpdatePacket() {
        return new net.minecraft.network.play.server.SPacketUpdateTileEntity(this.pos, 1, this.getUpdateTag());
    }

    @Override
    public void onDataPacket(net.minecraft.network.NetworkManager net,
                             net.minecraft.network.play.server.SPacketUpdateTileEntity pkt) {
        this.cfg.readFromNBT(pkt.getNbtCompound());
    }
}
