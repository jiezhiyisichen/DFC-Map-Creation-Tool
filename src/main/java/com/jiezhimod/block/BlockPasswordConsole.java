package com.jiezhimod.block;

import com.jiezhimod.JiezhiMod;
import com.jiezhimod.proxy.CommonProxy;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 密码控制台：占**一个完整方块**，右键打开配置界面。
 *
 * 它是这套密码系统的"大脑"：
 *   · **接收**红石信号，按界面里设定的动作去操作绑定的密码盘（锁定 / 解锁 / 清空）；
 *   · **输出**红石信号 —— 只有当绑定的密码盘报告"密码正确"时才通电。
 *
 * 绑定不在这里做：拿**密码连接器**蹲下右键密码盘、再蹲下右键本方块，
 * 见 {@link com.jiezhimod.item.ItemConnector}。
 */
public class BlockPasswordConsole extends BlockContainer {

    public BlockPasswordConsole() {
        super(Material.IRON);
        this.setRegistryName("jiezhimod", "password_console");
        this.setUnlocalizedName("password_console");
        this.setHardness(2.5F);
        this.setResistance(15.0F);
        this.setSoundType(SoundType.METAL);
        this.setHarvestLevel("pickaxe", 0);
        this.setCreativeTab(ModBlocks.TAB_REDSTONE);
    }

    /**
     * BlockContainer 默认 INVISIBLE（客户端整体跳过渲染）。
     * 这个方块用普通模型，必须显式改回 MODEL，否则是"能透视的洞"。
     */
    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TilePasswordConsole();
    }

    // ==================== 右键开界面 ====================

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        // 手上拿着连接器时，把右键让给它（蹲下=绑定，不蹲=交给方块开界面）
        if (player.getHeldItem(hand).getItem() instanceof com.jiezhimod.item.ItemConnector
                && player.isSneaking()) {
            return false;
        }
        if (world.isRemote) {
            return true;
        }
        player.openGui(JiezhiMod.instance, CommonProxy.GUI_PASSWORD_CONSOLE, world,
                pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    // ==================== 红石输出 ====================

    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    @Override
    public int getWeakPower(IBlockState state, IBlockAccess access, BlockPos pos, EnumFacing side) {
        TileEntity te = access.getTileEntity(pos);
        if (!(te instanceof TilePasswordConsole)) {
            return 0;
        }
        return ((TilePasswordConsole) te).isOutputOn() ? 15 : 0;
    }

    // 刻意不覆写 getStrongPower：与延时器同样的理由 ——
    // 强功率会隔着实心方块传出去，等于给信号开了第二条通路。

    // ==================== 生命周期 ====================

    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        world.notifyNeighborsOfStateChange(pos, this, true);
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        super.breakBlock(world, pos, state);
        // 拆掉之后输出要归零，否则邻居会一直以为这里有信号
        world.notifyNeighborsOfStateChange(pos, this, true);
    }
}
