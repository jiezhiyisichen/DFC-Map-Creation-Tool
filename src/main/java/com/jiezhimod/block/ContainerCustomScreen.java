package com.jiezhimod.block;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

/**
 * 自定义显示屏 GUI 的容器。
 *
 * 屏幕上的 8 个数据槽不是"物品栏格子"—— 它们是显示屏自己的配置，
 * 存在方块实体里，跟玩家背包没有关系。所以这个容器一个格子都不放，
 * 但它必须存在且非 null：FML 只有在服务端返回了 Container 时
 * 才会给客户端把窗口开起来（见 {@link com.jiezhimod.proxy.CommonProxy}）。
 */
public class ContainerCustomScreen extends Container {

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return true;
    }
}
