package com.jiezhimod.proxy;

import com.jiezhimod.block.ContainerCustomScreen;
import com.jiezhimod.block.ContainerDelay;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

/**
 * 服务端/通用代理，同时充当 IGuiHandler。
 *
 * 客户端 GUI 类只能在 ClientProxy 里引用（服务端不该加载 GuiScreen），
 * 所以这里只给服务端容器，getClientGuiElement 由 ClientProxy 覆写。
 */
public class CommonProxy implements IGuiHandler {

    /** 红石延时器 GUI 编号 */
    public static final int GUI_DELAY = 0;
    /** 自定义显示屏 GUI 编号（右键方块打开） */
    public static final int GUI_CUSTOM_SCREEN = 1;

    public void preInit() {
        // 服务端/通用侧无需注册客户端渲染事件
    }

    public void init() {
    }

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_DELAY) {
            return new ContainerDelay();
        }
        if (id == GUI_CUSTOM_SCREEN) {
            return new ContainerCustomScreen();
        }
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return null;
    }
}
