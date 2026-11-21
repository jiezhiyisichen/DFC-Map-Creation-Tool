package com.jiezhimod.block;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.util.math.BlockPos;

/**
 * 灯圈控制台的容器：**一个槽都没有**。
 *
 * <p>【为什么界面没有物品槽却还要有容器】1.12.2 的 GUI 打开流程是
 * "服务端先建容器 → 客户端再建界面"，服务端返回 null 会让玩家卡在一个
 * 没有容器的界面里（关不掉、也无法再打开别的）。所以给一个空容器做距离校验。</p>
 */
public class ContainerRingConsole extends Container {

    private final TileRingConsole tile;

    public ContainerRingConsole(TileRingConsole tile) {
        this.tile = tile;
    }

    public TileRingConsole getTile() {
        return this.tile;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (this.tile == null || this.tile.getWorld() == null) {
            return false;
        }
        BlockPos pos = this.tile.getPos();
        return player.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }
}
