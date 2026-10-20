package com.jiezhimod.proxy;

import com.jiezhimod.block.ContainerCustomScreen;
import com.jiezhimod.block.TileCustomScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

/**
 * 服务端/通用代理，同时充当 IGuiHandler。
 *
 * 客户端 GUI 类只能在 ClientProxy 里引用（服务端不该加载 GuiScreen），
 * 所以这里只给服务端容器，getClientGuiElement 由 ClientProxy 覆写。
 */
public class CommonProxy implements IGuiHandler {

    /** 自定义显示屏 GUI 编号（右键方块打开） */
    public static final int GUI_CUSTOM_SCREEN = 1;
    /** 密码控制台 GUI 编号（右键方块打开） */
    public static final int GUI_PASSWORD_CONSOLE = 2;
    /** 调试界面（调试棒蹲下右键本模组方块） */
    public static final int GUI_DEBUG = 3;

    public void preInit() {
        // 服务端/通用侧无需注册客户端渲染事件
    }

    public void init() {
    }

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_DEBUG) {
            return new com.jiezhimod.block.ContainerDebug(new BlockPos(x, y, z));
        }
        if (id == GUI_PASSWORD_CONSOLE) {
            // 界面本身没有物品槽，但服务端必须给出一个容器，否则客户端开不出界面
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof com.jiezhimod.block.TilePasswordConsole) {
                return new com.jiezhimod.block.ContainerPasswordConsole(
                        (com.jiezhimod.block.TilePasswordConsole) te);
            }
            return null;
        }
        if (id == GUI_CUSTOM_SCREEN) {
            // 容器现在要拿着方块实体（遥控器槽 / 导入 / 距离判定都要用）。
            // 找不到方块实体就返回 null —— 宁可不打开，也不能开出一个功能残缺的面板。
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileCustomScreen) {
                return new ContainerCustomScreen(player.inventory, (TileCustomScreen) te);
            }
            return null;
        }
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return null;
    }
}
