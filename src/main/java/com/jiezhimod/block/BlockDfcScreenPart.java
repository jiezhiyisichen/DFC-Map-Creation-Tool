package com.jiezhimod.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 显示器的**占位块**：4x3 结构里除控制器之外的 11 格。
 *
 * 它没有方块实体，也不参与逻辑，只负责三件事：
 *   1. 提供碰撞与选中框（玩家能踩到 / 点到它）；
 *   2. 记录朝向，让拆除时能从任意一格推回控制器；
 *   3. 被破坏时把整块显示器一起带走（并掉一个显示器物品）。
 *
 * 没有物品形态（不注册 ItemBlock）—— 玩家无法单独获得它，
 * 只能通过放置显示器来生成，破坏时也统一掉显示器本体。
 */
public class BlockDfcScreenPart extends Block {

    public static final PropertyEnum<EnumFacing> FACING = PropertyEnum.create("facing",
            EnumFacing.class, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST);

    private static final double HALF_THICK = 2.0D / 16.0D;

    public BlockDfcScreenPart() {
        super(Material.IRON);
        this.setRegistryName("jiezhimod", "dfc_screen_part");
        this.setUnlocalizedName("dfc_screen_part");
        this.setHardness(2.0F);
        this.setResistance(12.0F);
        this.setSoundType(net.minecraft.block.SoundType.METAL);
        this.setHarvestLevel("pickaxe", 0);
        this.setLightLevel(0.35F);   // 屏幕的一部分，跟着一起发光
        this.setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    // ==================== 状态 ====================

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    /** 只有 4 个朝向：meta 0~3，稳稳落在 1.12.2 的 16 槽限制内 */
    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex();
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return this.getDefaultState().withProperty(FACING, EnumFacing.getHorizontal(meta & 3));
    }

    // ==================== 拆除 ====================

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        super.breakBlock(world, pos, state);
        if (world.isRemote || ScreenMultiblock.removing) {
            return;
        }
        BlockPos controller = ScreenMultiblock.findController(world, pos);
        if (controller == null) {
            return;
        }
        IBlockState cs = world.getBlockState(controller);
        if (!(cs.getBlock() instanceof BlockDfcScreen)) {
            return;
        }
        ScreenMultiblock.removing = true;
        try {
            ScreenMultiblock.removeStructure(world, controller, cs.getValue(BlockDfcScreen.FACING), pos);
        } finally {
            ScreenMultiblock.removing = false;
        }
    }

    /**
     * 破坏任意一格都掉一个显示器物品（其余格是被 setBlockToAir 清掉的，不掉落）。
     *
     * 【为什么不是直接返回固定物品】
     * 现在有两种显示器共用这批占位块（核心显示器 / 部件监视器），
     * 拆一块部件监视器却掉出一块核心屏就穿帮了。所以这里先找出它属于哪个控制器，
     * 再问控制器"你掉什么"（{@link BlockDfcScreen#displayItem()}）。
     * 这一步安全的前提是：原版 harvestBlock（算掉落）发生在 breakBlock（拆结构）**之前**，
     * 调用到这里时占位块和控制器都还在。
     */
    @Override
    public List<ItemStack> getDrops(IBlockAccess world, BlockPos pos, IBlockState state, int fortune) {
        List<ItemStack> drops = new ArrayList<ItemStack>();
        Item item = ModBlocks.ITEM_DFC_SCREEN;
        if (world instanceof World) {
            BlockPos controller = ScreenMultiblock.findController((World) world, pos);
            if (controller != null) {
                Block controllerBlock = world.getBlockState(controller).getBlock();
                if (controllerBlock instanceof BlockDfcScreen) {
                    item = ((BlockDfcScreen) controllerBlock).displayItem();
                }
            }
        }
        drops.add(new ItemStack(item, 1));
        return drops;
    }

    /** 兜底路径（某些模组直接调它算掉落）：认不出控制器时按核心显示器给 */
    @Override
    public Item getItemDropped(IBlockState state, Random rand, int fortune) {
        return ModBlocks.ITEM_DFC_SCREEN;
    }

    @Override
    public int quantityDropped(Random random) {
        return 1;
    }

    // ==================== 交互 ====================

    /**
     * 占位块同样要响应右键 —— 玩家点屏幕中间时命中的就是这一格，
     * 只给控制器写交互会让"右键开关"在最常见的位置上失效。
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        return ScreenMultiblock.handleUse(world, pos, player, hand);
    }

    // ==================== 外形 ====================

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.INVISIBLE;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isNormalCube(IBlockState state, IBlockAccess world, BlockPos pos) {
        return false;
    }

    static AxisAlignedBB plateBox(EnumFacing f) {
        double t = HALF_THICK;
        switch (f) {
            case EAST:
            case WEST:
                return new AxisAlignedBB(0.5D - t, 0.0D, 0.0D, 0.5D + t, 1.0D, 1.0D);
            case SOUTH:
            case NORTH:
            default:
                return new AxisAlignedBB(0.0D, 0.0D, 0.5D - t, 1.0D, 1.0D, 0.5D + t);
        }
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return plateBox(state.getValue(FACING));
    }

    @Nullable
    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return plateBox(state.getValue(FACING));
    }
}
