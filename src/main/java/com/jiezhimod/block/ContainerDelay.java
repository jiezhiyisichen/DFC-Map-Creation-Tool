package com.jiezhimod.block;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

/**
 * 红石延时器 GUI 的容器：没有任何格子（纯设置界面），
 * 但服务端必须返回一个非 null 的 Container，FML 才会给客户端开窗口。
 */
public class ContainerDelay extends Container {

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return true;
    }
}
