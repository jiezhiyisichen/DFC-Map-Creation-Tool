package com.jiezhimod.block;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;

/**
 * 警示灯控制台的容器（v4.5）—— 没有槽位，纯粹是"打开一个界面"的载体。
 *
 * <p>{@code openGui} 必须给一个 Container，哪怕里面什么都不放。
 * 真正的数据在 {@link TileAlarmConsole} 里，界面读写都走它。</p>
 */
public class ContainerAlarmConsole extends Container {

    private final TileAlarmConsole tile;

    public ContainerAlarmConsole(InventoryPlayer inv, TileAlarmConsole tile) {
        this.tile = tile;
    }

    public TileAlarmConsole tile() {
        return this.tile;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return this.tile != null && this.tile.getWorld() != null
                && player.getDistanceSq(this.tile.getPos().getX() + 0.5D,
                this.tile.getPos().getY() + 0.5D, this.tile.getPos().getZ() + 0.5D) <= 64.0D;
    }
}
