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
import net.minecraft.world.World;

/**
 * 灯圈控制台（v4.4）：占一个方块，右键打开配置界面。
 *
 * <p>它是灯圈系统的"大脑"：接收红石信号，按界面里**多选**的动作
 * 去操作绑定的灯圈组（换色 / 开灯 / 关灯 / 闪烁，各带淡入淡出参数）。
 * 多台控制台用红石延迟串起来就是变色流程 —— 详见 {@link TileRingConsole}。</p>
 *
 * <p>绑定不在这里做：拿**激光链接器同款的灯圈链接器**
 * （{@link com.jiezhimod.item.ItemRingConnector}）蹲下右键灯圈、再蹲下右键本方块。</p>
 */
public class BlockRingConsole extends BlockContainer {

    public BlockRingConsole() {
        super(Material.IRON);
        this.setRegistryName("jiezhimod", "ring_console");
        this.setUnlocalizedName("ring_console");
        this.setHardness(2.5F);
        this.setResistance(15.0F);
        this.setSoundType(SoundType.METAL);
        this.setHarvestLevel("pickaxe", 0);
        this.setCreativeTab(com.jiezhimod.item.ModItems.TAB);
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
        return new TileRingConsole();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        // 手里拿着灯圈链接器且蹲下 = 绑定动作，把事件让给物品（不蹲 = 开界面）
        if (player.getHeldItem(hand).getItem() instanceof com.jiezhimod.item.ItemRingConnector
                && player.isSneaking()) {
            return false;
        }
        if (world.isRemote) {
            return true;
        }
        player.openGui(JiezhiMod.instance, CommonProxy.GUI_RING_CONSOLE, world,
                pos.getX(), pos.getY(), pos.getZ());
        return true;
    }
}
