package com.jiezhimod.block;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.util.math.BlockPos;

/**
 * 调试界面的容器：**一个槽都没有**，只用来做距离校验。
 *
 * 与 {@link ContainerPasswordConsole} 同样的理由 —— 1.12.2 的 GUI 是
 * "服务端先建容器、客户端再建界面"，服务端返回 null 会让玩家卡在一个
 * 没有容器的界面里（关不掉、也开不出别的）。
 *
 * 这里刻意**不持有方块实体**：调试界面只按坐标去查（客户端那份 TE 是镜像，
 * 服务端那份才是权威），少一层引用就少一处"拆了方块还攥着旧实例"的隐患。
 */
public class ContainerDebug extends Container {

    private final BlockPos pos;

    public ContainerDebug(BlockPos pos) {
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
