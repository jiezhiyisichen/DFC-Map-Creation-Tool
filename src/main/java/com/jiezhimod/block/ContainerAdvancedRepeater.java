package com.jiezhimod.block;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.util.math.BlockPos;

/**
 * 高级红石中继器界面的容器：**一个槽都没有**，只用来做距离校验。
 *
 * 1.12.2 的 GUI 是"服务端先建容器、客户端再建界面"两步走，服务端返回 null
 * 会让玩家卡在一个没有容器的界面里（关不掉、也开不出别的）—— 所以哪怕是纯配置界面，
 * 也必须有这么一个壳。理由见 {@link ContainerDebug}。
 */
public class ContainerAdvancedRepeater extends Container {

    private final BlockPos pos;

    public ContainerAdvancedRepeater(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (this.pos == null) {
            return false;
        }
        return player.getDistanceSq(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D,
                this.pos.getZ() + 0.5D) <= 64.0D;
    }
}
