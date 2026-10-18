package com.jiezhimod.block;

import com.jiezhimod.keypad.KeypadLayout;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 密码盘：**占一格、高半格**的触摸式数字键盘。
 *
 * 【触摸式】右键**不打开界面**，而是把手里的准心落在哪个键上就按哪个键
 * （{@link KeypadLayout#hitWorld} 用 {@code onBlockActivated} 给的 hitX/hitZ 定位）。
 * 所以这个方块没有任何 GUI，全部交互都在世界里完成。
 *
 * 【密码从哪来】每台的密码在方块实体里随机生成（{@link TileKeypad}），
 * 由渲染器画在旁边那张**便签纸**上 —— 玩家照着便签输就行，
 * 不需要任何指令或界面去查。
 *
 * 【为什么 getRenderType 是 INVISIBLE】底座和面板全部由 TESR 画
 * （面板是动态纹理，模型画不了）。漏掉这个覆盖的话方块会变成"能透视的洞"。
 */
public class BlockKeypad extends BlockContainer {

    /** 半格高的外形：水平占满 1 格，竖直只有上半 8/16 */
    private static final AxisAlignedBB SHAPE = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D);

    public BlockKeypad() {
        super(Material.IRON);
        this.setRegistryName("jiezhimod", "keypad");
        this.setUnlocalizedName("keypad");
        this.setHardness(2.0F);
        this.setResistance(12.0F);
        this.setSoundType(SoundType.METAL);
        this.setHarvestLevel("pickaxe", 0);
        this.setCreativeTab(ModBlocks.TAB_REDSTONE);
    }

    // ==================== 外形 ====================

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return SHAPE;
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return SHAPE;
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
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.INVISIBLE;
    }

    // ==================== 方块实体 ====================

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileKeypad();
    }

    // ==================== 触摸交互 ====================

    /**
     * 右键 = 按一个键。
     *
     * 【只在服务端处理】判定与比对必须服务端权威，客户端返回 true 只是
     * "这次右键我消耗掉了"，免得又弹出放置方块的逻辑。
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileKeypad)) {
            return true;
        }
        TileKeypad keypad = (TileKeypad) te;

        // 从哪一面点进来的，hitX/hitZ 的语义都不一样（原版给的是"这一面的局部坐标"）。
        // 干脆先用命中面把局部坐标换算回方块坐标，再交给 KeypadLayout 判键。
        float localX = hitX;
        float localZ = hitZ;
        switch (facing) {
            case UP:
                localX = hitX;
                localZ = hitZ;
                break;
            case DOWN:
                localX = hitX;
                localZ = 1.0F - hitZ;
                break;
            case NORTH:
                localX = 1.0F - hitX;
                localZ = hitY;
                break;
            case SOUTH:
                localX = hitX;
                localZ = hitY;
                break;
            case WEST:
                localX = hitZ;
                localZ = hitY;
                break;
            case EAST:
                localX = 1.0F - hitZ;
                localZ = hitY;
                break;
            default:
                break;
        }

        int key = KeypadLayout.hitWorld(localX, localZ);
        if (key < 0) {
            // 点在台面边缘 / 键缝里：不出声、不输入，避免"手滑就输错一位"
            return true;
        }
        boolean ok = keypad.press(key);
        if (ok) {
            world.playSound(null, pos, SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON,
                    SoundCategory.BLOCKS, 0.5F, 1.4F);
        } else {
            world.playSound(null, pos, SoundEvents.BLOCK_STONE_BUTTON_CLICK_OFF,
                    SoundCategory.BLOCKS, 0.4F, 0.8F);
        }
        return true;
    }

    // ==================== 生命周期 ====================

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        // 密码盘被拆时，连着它的控制台还在轮询 —— 让它下一次扫描自己发现"没盘了"
        world.notifyNeighborsOfStateChange(pos, this, true);
        super.breakBlock(world, pos, state);
    }
}
