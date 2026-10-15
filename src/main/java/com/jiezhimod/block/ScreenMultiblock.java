package com.jiezhimod.block;

import com.jiezhimod.dfc.ScreenStructure;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 显示器多方块的搬运与查找工具。
 *
 * 【为什么结构里只放"朝向"，不放 part 编号】
 * 1.12.2 的 {@code Block} 内部有几个**按 meta 索引的定长 16 数组**
 * （harvestTool / harvestLevel …），所以任何方块状态的 meta 都必须落在 0~15。
 * 最初的设计是 part(12) x facing(4) = 48 个状态、meta 取 0~47，
 * 结果 {@code setHarvestLevel} 直接在 {@code harvestTool[24]} 上越界崩掉。
 * 现在把 part 编号彻底拿掉：格子属于哪个 part 完全由几何推导出来
 * （controller 坐标 + 朝向 -> 12 个格子），两个方块各自只需要 4 个朝向变体。
 *
 * 【代价】拆除时必须"先找到控制器"才能推算出其余格子，于是需要一次小范围扫描。
 * 扫描半径只有 4x3x4，且只在拆方块时发生一次，成本可以忽略。
 */
public final class ScreenMultiblock {

    /**
     * 递归保护标志：整体拆除时每一格都会再走到 breakBlock，
     * 不拦住就会互相触发（而且是没有尽头的互相触发）。
     * 服务端单线程执行，静态标志足够。
     */
    static boolean removing = false;

    private ScreenMultiblock() {
    }

    /** 这一格是不是结构的一部分（控制器或占位块） */
    public static boolean isPart(Block block) {
        return block instanceof BlockDfcScreen || block instanceof BlockDfcScreenPart;
    }

    /**
     * 判断一个控制器的 12 格几何范围内是否包含 target。
     * 刻意**不检查那些格子放的是什么** —— 拆除时被拆的那一格已经是空气了，
     * 检查方块类型会让它认不出自己的结构。几何上两个显示器不可能重叠
     * （放置时会因空间不足而失败），所以只用坐标判断是安全的。
     */
    public static boolean covers(BlockPos controller, EnumFacing facing, BlockPos target) {
        for (int part = 0; part < ScreenStructure.PARTS; part++) {
            BlockPos p = ScreenStructure.partPos(controller, part, facing);
            if (p != null && p.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从任意一格（含已经被拆成空气的那一格）找到它所属显示器的控制器坐标。
     *
     * @return 控制器坐标；找不到返回 null
     */
    public static BlockPos findController(World world, BlockPos pos) {
        // 快路径：自己就是控制器
        if (world.getBlockState(pos).getBlock() instanceof BlockDfcScreen) {
            return pos;
        }
        for (int dy = -ScreenStructure.ROWS; dy <= ScreenStructure.ROWS; dy++) {
            for (int dz = -ScreenStructure.COLS; dz <= ScreenStructure.COLS; dz++) {
                for (int dx = -ScreenStructure.COLS; dx <= ScreenStructure.COLS; dx++) {
                    BlockPos p = pos.add(dx, dy, dz);
                    IBlockState s = world.getBlockState(p);
                    if (!(s.getBlock() instanceof BlockDfcScreen)) {
                        continue;
                    }
                    if (covers(p, s.getValue(BlockDfcScreen.FACING), pos)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** 结构是否完整（控制器 + 11 个占位块都在） */
    public static boolean structureIntact(World world, BlockPos controller, EnumFacing facing) {
        for (int part = 0; part < ScreenStructure.PARTS; part++) {
            BlockPos p = ScreenStructure.partPos(controller, part, facing);
            if (p == null) {
                return false;
            }
            Block b = world.getBlockState(p).getBlock();
            if (part == 0) {
                if (!(b instanceof BlockDfcScreen)) {
                    return false;
                }
            } else if (!(b instanceof BlockDfcScreenPart)) {
                return false;
            }
        }
        return true;
    }

    /** 结构里还有几格在（用于界面上提示"结构不完整"） */
    public static int countParts(World world, BlockPos controller, EnumFacing facing) {
        int n = 0;
        for (int part = 0; part < ScreenStructure.PARTS; part++) {
            BlockPos p = ScreenStructure.partPos(controller, part, facing);
            if (p != null && isPart(world.getBlockState(p).getBlock())) {
                n++;
            }
        }
        return n;
    }

    /** 拆除整个结构。
     *
     * @param except 跳过这一格（就是玩家正在拆的那格，交给 MC 自己处理掉落）
     */
    public static void removeStructure(World world, BlockPos controller, EnumFacing facing, BlockPos except) {
        for (int part = 0; part < ScreenStructure.PARTS; part++) {
            BlockPos p = ScreenStructure.partPos(controller, part, facing);
            if (p == null || p.equals(except)) {
                continue;
            }
            if (isPart(world.getBlockState(p).getBlock())) {
                // setBlockToAir 不会触发掉落，正好 —— 只让玩家拆的那一格掉物品
                world.setBlockToAir(p);
            }
        }
    }

    /**
     * 控制器与占位块**共用**的右键处理。
     *
     * 玩家十有八九点是屏幕正中间，而那一格是占位块 —— 如果只给控制器写交互，
     * "右键开关"在这个最常见的位置上会毫无反应。所以两个方块都转发到这里。
     *
     * 三条分支：
     *   1. 手持 DFC 遥控器 + 目标屏是**自定义屏** + **蹲下** -> 把遥控器里记录的设备接进来；
     *   2. 手持 DFC 遥控器 + 其它情况 -> 交给遥控器（核心屏/监视器是"绑定反应堆源"）；
     *   3. 空手（或非遥控器）：自定义屏打开 GUI，核心屏/监视器切换开关。
     *
     * 【为什么自定义屏的"不蹲下右键"要落到 3】它的内容得在 GUI 里编排，
     * 所以"右键打开面板"是它的主操作；绑定设备反而成了次要动作，挪到蹲下。
     */
    public static boolean handleUse(World world, BlockPos pos, EntityPlayer player, EnumHand hand) {
        net.minecraft.item.ItemStack held = player.getHeldItem(hand);
        if (held != null && held.getItem() instanceof com.jiezhimod.item.ItemDfcRemote) {
            BlockPos cp = findController(world, pos);
            boolean custom = cp != null && world.getTileEntity(cp) instanceof TileCustomScreen;
            if (!(custom && !player.isSneaking())) {
                return ((com.jiezhimod.item.ItemDfcRemote) held.getItem()).useOnScreen(held, world, pos, player);
            }
        }
        if (world.isRemote) {
            return true;
        }
        BlockPos controllerPos = findController(world, pos);
        if (controllerPos == null) {
            return false;
        }
        net.minecraft.tileentity.TileEntity te = world.getTileEntity(controllerPos);
        if (te instanceof TileCustomScreen) {
            // 自定义屏：右键开 GUI（开机按钮在界面里），而不是直接开关机
            player.openGui(com.jiezhimod.JiezhiMod.instance,
                    com.jiezhimod.proxy.CommonProxy.GUI_CUSTOM_SCREEN, world,
                    controllerPos.getX(), controllerPos.getY(), controllerPos.getZ());
            return true;
        }
        if (te instanceof TileDfcScreen) {
            ((TileDfcScreen) te).togglePower(player);
            return true;
        }
        return false;
    }
}
