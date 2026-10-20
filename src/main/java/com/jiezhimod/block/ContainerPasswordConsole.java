package com.jiezhimod.block;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.util.math.BlockPos;

/**
 * 密码控制台的容器：**一个槽都没有**。
 *
 * 【为什么界面没有物品槽却还要有容器】1.12.2 的 GUI 打开流程是
 * "服务端先建容器 → 客户端再建界面"，服务端那一侧返回 null 会让玩家
 * 卡在一个没有容器的界面里（关不掉、也无法再打开别的）。所以给一个空容器，
 * 只用来做距离校验。
 */
public class ContainerPasswordConsole extends Container {

    private final TilePasswordConsole tile;

    public ContainerPasswordConsole(TilePasswordConsole tile) {
        this.tile = tile;
    }

    public TilePasswordConsole getTile() {
        return this.tile;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (this.tile == null || this.tile.getWorld() == null) {
            return false;
        }
        BlockPos pos = this.tile.getPos();
        // 与方块实体保持 8 格以内（原版箱子同款）
        return player.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }
}
