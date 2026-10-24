package com.jiezhimod.block;

import com.jiezhimod.JiezhiMod;
import com.jiezhimod.proxy.CommonProxy;
import com.jiezhimod.repeater.RepeaterDelay;
import net.minecraft.block.Block;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.init.Blocks;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.IBlockAccess;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;

/**
 * 高级红石中继器 —— **外形、贴图、红石原理全部照搬原版中继器**，
 * 只把"你能设多长的延迟"这一项放开：原版只能 1~4 红石刻，
 * 这里 1 ~ 1200 tick（0.05 ~ 60 秒），右键开界面慢慢调。
 *
 * <h3>和原版一致的部分</h3>
 * <ul>
 *   <li>信号只从**背面**进、只往**正面**出（{@link #FACING} 是输出方向）；</li>
 *   <li>上升沿 / 下降沿**对称延迟**，比延迟短的脉冲被整个吞掉（见 {@link com.jiezhimod.repeater.RepeaterTimer}）；</li>
 *   <li>输出是 15 级，弱充能正面 —— 拉得动活塞、点得亮红石灯、能接红石粉；</li>
 *   <li>左右两侧被**上电的中继器 / 比较器**顶住时进入**锁定**状态，输出保持不变；</li>
 *   <li>外形只有 2/16 格高，不是导体，红石粉只能接前后两面。</li>
 * </ul>
 *
 * <h3>只有这里不一样</h3>
 * 右键**不循环切延迟**，而是打开 {@code GuiAdvancedRepeater}：可以用 tick 或秒为单位
 * 直接填数值（也不用把 60 秒换算成"按多少次右键"）。延迟存在方块实体里，
 * 拆下来会写进物品 NBT，搬走再放回去不丢设置。
 *
 * <h3>为什么不用 BlockRedstoneDiode</h3>
 * 原版的延迟是 {@code delay=1..4} 这个**方块状态属性**，它的 blockstate / 模型 /
 * 计划刻全绑死在这 4 个值上；延迟真要做成 1~1200，属性枚举会炸成 1200 个变体。
 * 所以这里自己实现状态机，把延迟挪进 {@link TileAdvancedRepeater}。
 */
public class BlockAdvancedRepeater extends Block {

    /** 输出方向（= 信号流出的那一面） */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);
    /** 输出是否带电（也决定用亮着的模型） */
    public static final PropertyBool POWERED = PropertyBool.create("powered");
    /** 是否被侧面的中继器锁定（原版也有这个属性，锁定时模型中间会多一根黑条） */
    public static final PropertyBool LOCKED = PropertyBool.create("locked");

    /** 外形照抄原版中继器：高度只有 2/16 */
    private static final AxisAlignedBB SHAPE = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 0.125D, 1.0D);

    public BlockAdvancedRepeater() {
        super(Material.CIRCUITS);
        this.setRegistryName("jiezhimod", "advanced_repeater");
        this.setUnlocalizedName("advanced_repeater");
        // 原版中继器硬度 0：空手一下就没
        this.setHardness(0.0F);
        this.setResistance(0.0F);
        this.setSoundType(SoundType.WOOD);
        this.setLightOpacity(0);
        this.setCreativeTab(ModBlocks.TAB_REDSTONE);
        this.setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(POWERED, Boolean.FALSE)
                .withProperty(LOCKED, Boolean.FALSE));
    }

    // ==================== 形状 ====================

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return SHAPE;
    }

    @Nullable
    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return SHAPE;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    /** 必须留 MODEL：本方块用 json 模型渲染（底座 + 两个红石火把），不是 TESR */
    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    // ==================== 方块状态 ====================

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, new IProperty[]{FACING, POWERED, LOCKED});
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return this.getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(POWERED, Boolean.valueOf((meta & 4) != 0))
                .withProperty(LOCKED, Boolean.valueOf((meta & 8) != 0));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = state.getValue(FACING).getHorizontalIndex();
        if (state.getValue(POWERED)) {
            meta |= 4;
        }
        if (state.getValue(LOCKED)) {
            meta |= 8;
        }
        return meta;
    }

    // ==================== 放置 / 拆除 ====================

    /** 和原版中继器一样：下面必须有实心的顶面（不能悬空放） */
    @Override
    public boolean canPlaceBlockAt(World world, BlockPos pos) {
        return world.getBlockState(pos.down()).isTopSolid();
    }

    /** 底下的方块被拆掉时自己也掉下来（原版中继器同款） */
    @Override
    public void neighborChanged(IBlockState state, World world, BlockPos pos, Block block,
                                BlockPos fromPos) {
        if (!world.getBlockState(pos.down()).isTopSolid()) {
            this.dropBlockAsItem(world, pos, state, 0);
            world.setBlockToAir(pos);
        }
    }

    /** 输出方向 = 玩家看的方向（和原版一致：输入朝向玩家那一侧） */
    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing,
                                            float hitX, float hitY, float hitZ, int meta,
                                            EntityLivingBase placer) {
        return this.getDefaultState().withProperty(FACING, placer.getHorizontalFacing());
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        int delay = readDelay(stack);
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileAdvancedRepeater) {
            TileAdvancedRepeater tile = (TileAdvancedRepeater) te;
            tile.setDelay(delay);
            // 放下去这一刻可能背面已经有信号：先算一次，别等玩家再来碰一下才亮
            tile.updateNow();
        }
    }

    @Override
    public void getDrops(NonNullList<ItemStack> drops, IBlockAccess world, BlockPos pos,
                         IBlockState state, int fortune) {
        drops.add(withDelay(new ItemStack(this), world, pos));
    }

    @Override
    public ItemStack getPickBlock(IBlockState state, RayTraceResult target, World world,
                                  BlockPos pos, EntityPlayer player) {
        return withDelay(new ItemStack(this), world, pos);
    }

    /** 把方块实体里的延迟写进物品 NBT（拆下来搬走不丢设置） */
    private static ItemStack withDelay(ItemStack stack, IBlockAccess world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileAdvancedRepeater) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger(TileAdvancedRepeater.TAG_DELAY, ((TileAdvancedRepeater) te).getDelay());
            stack.setTagCompound(tag);
        }
        return stack;
    }

    /** 读取物品上的延迟；没有 NBT 就用默认 2 tick */
    public static int readDelay(ItemStack stack) {
        if (stack != null && stack.hasTagCompound()
                && stack.getTagCompound().hasKey(TileAdvancedRepeater.TAG_DELAY)) {
            return RepeaterDelay.clamp(
                    stack.getTagCompound().getInteger(TileAdvancedRepeater.TAG_DELAY));
        }
        return RepeaterDelay.DEFAULT_TICKS;
    }

    // ==================== 方块实体 ====================

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileAdvancedRepeater();
    }

    // ==================== 红石 ====================

    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    /** 只在**正面**给 15 级弱充能（原版中继器同款） */
    @Override
    public int getWeakPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        if (!state.getValue(POWERED)) {
            return 0;
        }
        return state.getValue(FACING) == side ? 15 : 0;
    }

    /** 红石粉只接前后两面（左右是绝缘的，和原版一致） */
    @Override
    public boolean canConnectRedstone(IBlockState state, IBlockAccess world, BlockPos pos,
                                      @Nullable EnumFacing side) {
        return side != null && side.getAxis() == state.getValue(FACING).getAxis();
    }

    /**
     * 背面当前有没有信号（红石粉、中继器、比较器、拉杆……都算"往我这里发的弱电"）。
     *
     * 【为什么要单独补一段红石粉】{@code getRedstonePower(back, facing)} 对红石粉未必
     * 返回读数（取决于那根粉有没有朝这个方向"连通"），原版
     * {@code BlockRedstoneDiode.calculateInputStrength} 就为这件事专门加了一条
     * "背面是红石粉就直接读它的 POWER 属性"的分支。这里照抄同款兜底，
     * 否则会出现"粉明明亮着，高级红石中继器就是不亮"。
     */
    public static boolean hasInput(World world, BlockPos pos, IBlockState state) {
        EnumFacing facing = state.getValue(FACING);
        BlockPos back = pos.offset(facing.getOpposite());
        int power = world.getRedstonePower(back, facing);
        if (power > 0) {
            return true;
        }
        IBlockState backState = world.getBlockState(back);
        return backState.getBlock() == Blocks.REDSTONE_WIRE
                && backState.getValue(BlockRedstoneWire.POWER) > 0;
    }

    /**
     * 是否被侧面锁住：左右相邻的**上电中继器 / 比较器**如果正对着我，就把我按住。
     * 前后同轴的不算（那是串联，不是锁定）。
     */
    public static boolean isLocked(World world, BlockPos pos, IBlockState state) {
        EnumFacing facing = state.getValue(FACING);
        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
            if (side.getAxis() == facing.getAxis()) {
                continue;
            }
            BlockPos otherPos = pos.offset(side);
            IBlockState other = world.getBlockState(otherPos);
            Block block = other.getBlock();

            // 【为什么不用 BlockRedstoneDiode.POWERED】原版中继器/比较器不是用一个
            // powered 属性，而是**拆成两个方块实例**（unpowered_repeater / powered_repeater）。
            // BlockRedstoneDiode 里也没有公开的 POWERED 字段，只有 protected 的
            // isRepeaterPowered —— 所以这里直接按方块实例判断，最直白也最不容易错。
            if (block == Blocks.POWERED_REPEATER || block == Blocks.POWERED_COMPARATOR) {
                if (other.getValue(BlockHorizontal.FACING) == side.getOpposite()) {
                    return true;
                }
                continue;
            }
            // 自己这种"延迟存方块实体"的中继器：按方块状态里的 POWERED 判断
            if (block instanceof BlockAdvancedRepeater) {
                if (other.getValue(POWERED) && other.getValue(FACING) == side.getOpposite()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 交互 ====================

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state,
                                    EntityPlayer player, EnumHand hand, EnumFacing side,
                                    float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            // 客户端返回 true 只是"这次点击被我吃掉了"，界面由服务端发 openGui 打开
            return true;
        }
        world.playSound(null, pos, net.minecraft.init.SoundEvents.BLOCK_LEVER_CLICK,
                SoundCategory.BLOCKS, 0.3F, 1.4F);
        player.openGui(JiezhiMod.instance, CommonProxy.GUI_ADVANCED_REPEATER, world,
                pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    // ==================== 提示 ====================

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
                               ITooltipFlag flag) {
        int delay = readDelay(stack);
        tooltip.add(TextFormatting.GRAY
                + I18n.format("tooltip.jiezhimod.advanced_repeater.0"));
        tooltip.add(TextFormatting.AQUA
                + I18n.format("tooltip.jiezhimod.advanced_repeater.1"));
        tooltip.add(TextFormatting.AQUA
                + I18n.format("tooltip.jiezhimod.advanced_repeater.2",
                RepeaterDelay.MIN_TICKS, RepeaterDelay.secondsText(RepeaterDelay.MIN_TICKS),
                RepeaterDelay.MAX_TICKS, RepeaterDelay.secondsText(RepeaterDelay.MAX_TICKS)));
        tooltip.add(TextFormatting.DARK_GRAY
                + I18n.format("tooltip.jiezhimod.advanced_repeater.3",
                delay, RepeaterDelay.secondsText(delay)));
    }

    /** 拆掉时返还"高级红石中继器"本体（不是原版中继器、也不是空手） */
    @Override
    public Item getItemDropped(IBlockState state, Random rand, int fortune) {
        return ModBlocks.ITEM_ADVANCED_REPEATER;
    }
}
