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
    /** 高级红石中继器的延迟设置界面（右键方块打开） */
    public static final int GUI_ADVANCED_REPEATER = 4;
    /**
     * 激光状态显示器的面板（右键方块打开）。
     *
     * 【为什么不能和 {@link #GUI_CUSTOM_SCREEN} 共用一个号】两者的容器不一样：
     * 自定义屏是 8 条数据槽 + 遥控器，激光屏是 4 台激光 + 激光链接器。
     * 共用编号就只能开成同一个界面（v4.2 就是这样，于是激光屏一开就是 8 行）。
     */
    public static final int GUI_LASER_SCREEN = 5;

    public void preInit() {
        // 服务端/通用侧无需注册客户端渲染事件
    }

    public void init() {
    }

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        // 排错用：GUI 打不开时先看这一行有没有出现，判断是"没走到这里"还是"容器没建出来"
        com.jiezhimod.JiezhiMod.warn("[GuiServer] id=" + id + " pos=" + x + "," + y + "," + z
                + " te=" + (world.getTileEntity(new BlockPos(x, y, z)) == null ? "null"
                : world.getTileEntity(new BlockPos(x, y, z)).getClass().getSimpleName()));
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
        if (id == GUI_ADVANCED_REPEATER) {
            // 纯配置界面，没有物品槽；但服务端必须给出容器，否则客户端开不出界面
            return new com.jiezhimod.block.ContainerAdvancedRepeater(new BlockPos(x, y, z));
        }
        if (id == GUI_LASER_SCREEN) {
            // 激光屏的面板是**独立的一份**（4 台激光 + 激光链接器槽），
            // 不能再落回自定义屏那个容器，否则又变回 8 行。
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof com.jiezhimod.block.TileLaserScreen) {
                return new com.jiezhimod.block.ContainerLaserScreen(
                        player.inventory, (com.jiezhimod.block.TileLaserScreen) te);
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
