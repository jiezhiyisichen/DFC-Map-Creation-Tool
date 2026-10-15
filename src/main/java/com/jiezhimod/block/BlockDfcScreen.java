package com.jiezhimod.block;

import com.jiezhimod.dfc.ScreenStructure;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;

/**
 * DFC 监控显示器 —— 4 宽 x 3 高 x 1 厚 多方块的**控制器**（结构里的 part 0）。
 *
 * 【放置】放下任意一格会自动铺满 4x3；空间不够则整体取消并返还物品。
 * 【拆除】破坏任意一格（控制器或占位块），整个结构一起消失，掉 1 个物品。
 * 【朝向】正面朝向玩家。
 * 【下倾角】由放置瞬间的视线俯仰决定（低头放 = 更往下倾），存在方块实体里。
 * 【渲染】方块自身不画模型（getRenderType = INVISIBLE），全部交给 TESR ——
 *   面板要按玩家放置的角度整体倾斜，静态 JSON 模型表达不了这个自由度。
 *
 * 【状态设计】只有 FACING 一个属性（4 个变体，meta 0~3）。
 * 结构位置不写进方块状态：1.12.2 的 Block 内部有按 meta 索引的定长 16 数组，
 * 状态数一旦超过 16 就会在 setHarvestLevel 之类的调用上越界崩溃。
 * 见 {@link ScreenMultiblock} 的说明。
 */
public class BlockDfcScreen extends BlockContainer {

    /** 面板正面的水平朝向（只有 4 个水平方向） */
    public static final PropertyEnum<EnumFacing> FACING = PropertyEnum.create("facing",
            EnumFacing.class, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST);

    public BlockDfcScreen() {
        this("dfc_screen", "dfc_screen");
    }

    /**
     * 供子类使用：{@link BlockDfcMonitor} 与核心屏是同一台硬件，只差注册名。
     *
     * 【为什么必须走构造参数，而不能让子类自己再 setRegistryName】
     * {@code IForgeRegistryEntry.setRegistryName} 在第二次调用时会直接抛
     * {@code IllegalStateException: Attempted to set registry name with existing registry name!}
     * —— 父类构造器里已经设过 "dfc_screen"，子类再设 "dfc_monitor" 就会在
     * {@code ModBlocks.<clinit>} 里静态初始化失败，整个 mod 加载即崩。
     * 所以注册名只能在构造期一次定下来。
     *
     * @param registryName 注册路径（不含 "jiezhimod:" 前缀）
     * @param unlocalizedName 语言键后缀，对应 lang 里的 {@code tile.<名字>.name}
     */
    protected BlockDfcScreen(String registryName, String unlocalizedName) {
        super(Material.IRON);
        this.setRegistryName("jiezhimod", registryName);
        this.setUnlocalizedName(unlocalizedName);
        this.setHardness(2.0F);
        this.setResistance(12.0F);
        this.setSoundType(net.minecraft.block.SoundType.METAL);
        this.setHarvestLevel("pickaxe", 0);
        this.setLightLevel(0.35F);   // 亮着的屏幕会微微照亮周围
        this.setCreativeTab(ModBlocks.TAB_SCREEN);
        this.setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    // ==================== 方块状态 ====================

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex();
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return this.getDefaultState().withProperty(FACING, EnumFacing.getHorizontal(meta & 3));
    }

    // ==================== 放置 ====================

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing,
                                            float hitX, float hitY, float hitZ, int meta,
                                            EntityLivingBase placer, EnumHand hand) {
        EnumFacing f = placer.getHorizontalFacing().getOpposite();
        if (f.getAxis() == EnumFacing.Axis.Y) {
            f = EnumFacing.NORTH;
        }
        return this.getDefaultState().withProperty(FACING, f);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        if (world.isRemote) {
            return;
        }
        EnumFacing f = state.getValue(FACING);
        // 先确认 4x3 都放得下，放不下就整体取消 —— 半块显示器比不放更糟
        for (int part = 1; part < ScreenStructure.PARTS; part++) {
            BlockPos bp = ScreenStructure.partPos(pos, part, f);
            if (bp == null || !world.getBlockState(bp).getBlock().isReplaceable(world, bp)) {
                cancelPlacement(world, pos, placer);
                return;
            }
        }
        IBlockState partState = ModBlocks.DFC_SCREEN_PART.getDefaultState().withProperty(FACING, f);
        for (int part = 1; part < ScreenStructure.PARTS; part++) {
            BlockPos bp = ScreenStructure.partPos(pos, part, f);
            world.setBlockState(bp, partState, 3);
        }

        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileDfcScreen) {
            TileDfcScreen t = (TileDfcScreen) te;
            // 下倾角取放置瞬间的视线俯仰
            t.setTilt(ScreenStructure.tiltFromPitch(placer.rotationPitch));
            // 放好就直接开机。原来默认是关机，而关机画面几乎全黑，
            // 玩家从下面抬头只看到一块黑板，完全不知道这东西是块屏、更不知道要右键。
            t.powerOn();
        }
        if (placer instanceof EntityPlayer) {
            ((EntityPlayer) placer).sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + "Display mounted (4x3, hanging down from here). "
                            + "Right-click to power on, or bind a core with the DFC remote."), false);
        }
    }

    /** 空间不足：撤掉刚放下的那一格并把物品还给玩家 */
    private void cancelPlacement(World world, BlockPos pos, EntityLivingBase placer) {
        world.setBlockToAir(pos);
        if (placer instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) placer;
            ItemStack give = new ItemStack(displayItem());
            if (!p.inventory.addItemStackToInventory(give)) {
                p.dropItem(give, false);
            }
            p.sendStatusMessage(new TextComponentString(
                    TextFormatting.RED + "[jiezhimod] Not enough space for a 4x3 display."), false);
        }
    }

    /**
     * 这台显示器对应的物品。
     *
     * 部件监视器 {@link BlockDfcMonitor} 直接继承本类，只换注册名与显示的页面 ——
     * 破坏与"放不下返还"都必须掉**它自己**那个物品，否则会凭空变出一块核心屏。
     */
    protected Item displayItem() {
        return ModBlocks.ITEM_DFC_SCREEN;
    }

    // ==================== 拆除 ====================

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        super.breakBlock(world, pos, state);
        if (world.isRemote || ScreenMultiblock.removing) {
            return;
        }
        ScreenMultiblock.removing = true;
        try {
            ScreenMultiblock.removeStructure(world, pos, state.getValue(FACING), pos);
        } finally {
            ScreenMultiblock.removing = false;
        }
    }

    @Override
    public Item getItemDropped(IBlockState state, Random rand, int fortune) {
        return displayItem();
    }

    @Override
    public int quantityDropped(Random random) {
        return 1;
    }

    // ==================== 交互 ====================

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        // 手持遥控器 -> 绑定流程；否则切换开关。与占位块共用同一套逻辑
        return ScreenMultiblock.handleUse(world, pos, player, hand);
    }

    // ==================== 方块实体 ====================

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileDfcScreen();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileDfcScreen();
    }

    // ==================== 外形 / 渲染 ====================

    /** 全部交给 TESR 画，方块模型不参与（面板要能整体倾斜） */
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

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return BlockDfcScreenPart.plateBox(state.getValue(FACING));
    }

    @Nullable
    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return BlockDfcScreenPart.plateBox(state.getValue(FACING));
    }

    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        super.onBlockAdded(world, pos, state);
        // 让客户端拿到 TE 的初始数据（开关状态、倾斜角、绑定坐标）
        world.notifyBlockUpdate(pos, state, state, 3);
    }

    // ==================== 提示 ====================

    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + "Occupies 4 wide x 3 tall = " + ScreenStructure.PARTS
                + " blocks (ceiling-mounted)");
        tooltip.add(TextFormatting.GRAY + "Place against the underside of a ceiling to hang it overhead");
        tooltip.add(TextFormatting.GRAY + "Tilt follows your look angle when placed");
        tooltip.add(TextFormatting.AQUA + "Right-click: power on / off");
        tooltip.add(TextFormatting.AQUA + "Right-click with DFC remote: bind a core");
    }
}
