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

    /**
     * 底下的方块被拆掉时自己也掉下来（原版中继器同款）；否则刷新一次。
     *
     * 【为什么要顺手排一个计划刻】计时主体是方块实体里的计数器（每刻 +1），
     * 但**万一那块屏所在区块的方块实体没在逐刻跑**（区块刚加载、被别人家的
     * chunk 优化 mod 跳过之类），计数器就会停在原地 —— 延迟越长越像"根本不动"。
     * 这里在每一个边沿之后按 delay 排一个计划刻，到点了直接按当前输入对齐，
     * 相当于给长延迟上了一份保险。计数正常时它就是一次空转（状态已经一致）。
     */
    @Override
    public void neighborChanged(IBlockState state, World world, BlockPos pos, Block block,
                                BlockPos fromPos) {
        if (!world.getBlockState(pos.down()).isTopSolid()) {
            this.dropBlockAsItem(world, pos, state, 0);
            world.setBlockToAir(pos);
            return;
        }
        if (world.isRemote) {
            return;
        }
        TileEntity te = world.getTileEntity(pos);
        int delay = te instanceof TileAdvancedRepeater
                ? ((TileAdvancedRepeater) te).getDelay() : RepeaterDelay.DEFAULT_TICKS;
        world.scheduleUpdate(pos, this, Math.max(1, delay));
        if (te instanceof TileAdvancedRepeater) {
            // 锁定态要立刻反映（锁定本身不该有延迟）
            ((TileAdvancedRepeater) te).recompute(false);
        }
    }

    /** 计划刻到点：按当前输入把输出对齐（长延迟的保险，见 neighborChanged 的说明） */
    @Override
    public void updateTick(World world, BlockPos pos, IBlockState state, Random rand) {
        if (world.isRemote) {
            return;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileAdvancedRepeater) {
            ((TileAdvancedRepeater) te).recompute(true);
        }
    }

    /** 放下去先排一 tick，让背面本来就有信号的场合能自己亮起来 */
    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        super.onBlockAdded(world, pos, state);
        if (!world.isRemote) {
            world.scheduleUpdate(pos, this, 1);
        }
    }

    /**
     * 朝向**完全照抄原版** {@code BlockRedstoneDiode}：{@code FACING = 视线反方向}。
     *
     * 【为什么这里必须和原版一致，不能自创】中继器要和**原版中继器 / 比较器 / 红石粉**
     * 对接，而"哪一面算输入、哪一面算输出"是原版定死的：
     * 只要有一处和原版的理解不同，实机表现就是"原版那颗亮着，我这颗怎么都不动"
     * （v4.2 及之前就是这样：read 输入读的是 FACING 的背面）。
     * 现在四条（放置 / 读输入 / 给弱充能 / 锁定判据）全部按原版写，模型旋转也是抄原版的
     * blockstate，所以朝向、模型、信号流向三者天然一致。
     */
    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing,
                                            float hitX, float hitY, float hitZ, int meta,
                                            EntityLivingBase placer) {
        return this.getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
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

    /** 原版口径：{@code side == FACING} 时给 15（见 getStateForPlacement 的说明） */
    @Override
    public int getWeakPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        if (!state.getValue(POWERED)) {
            return 0;
        }
        return state.getValue(FACING) == side ? 15 : 0;
    }

    /** 强充能与弱充能同口径（原版 {@code BlockRedstoneDiode} 也是这么转发） */
    @Override
    public int getStrongPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return getWeakPower(state, world, pos, side);
    }

    /** 红石粉只接前后两面（左右是绝缘的，和原版一致） */
    @Override
    public boolean canConnectRedstone(IBlockState state, IBlockAccess world, BlockPos pos,
                                      @Nullable EnumFacing side) {
        return side != null && side.getAxis() == state.getValue(FACING).getAxis();
    }

    /**
     * 输入侧当前有没有信号（红石粉、中继器、比较器、拉杆……都算）。
     *
     * 【输入侧 = FACING 那一面】照抄原版 {@code calculateInputStrength}
     * （{@code pos.offset(FACING)} + {@code getRedstonePower(那个格子, FACING)}）。
     * 这四个口径（放置 / 读输入 / 给弱充能 / 锁定）必须整套照原版，
     * 混着写就会变成"原版喂不进信号"——v4.2 的实机问题正是这么来的。
     *
     * 【为什么还要单独补一段红石粉】{@code getRedstonePower} 对红石粉未必返回读数
     * （取决于那根粉有没有朝这个方向连通），原版为此专门加了一条
     * "相邻是红石粉就直接读它的 POWER"的分支。不补会出现"粉明明亮着，中继器就是不亮"。
     */
    public static boolean hasInput(World world, BlockPos pos, IBlockState state) {
        EnumFacing facing = state.getValue(FACING);
        BlockPos src = pos.offset(facing);
        int power = world.getRedstonePower(src, facing);
        if (power > 0) {
            return true;
        }
        IBlockState srcState = world.getBlockState(src);
        return srcState.getBlock() == Blocks.REDSTONE_WIRE
                && srcState.getValue(BlockRedstoneWire.POWER) > 0;
    }

    /**
     * 左右两侧是不是被"上电的中继器 / 比较器 / 红石块 / 红石粉"顶着。
     *
     * 【为什么改成原版那套判据】原版 {@code BlockRedstoneRepeater.isLocked} 看的是
     * 两侧的 {@code getPowerOnSides}：红石块给 15、红石粉读 POWER、中继器/比较器走
     * {@code getStrongPower(邻居坐标, 朝我这一侧)}（也就是**必须朝着我**才算）。
     *
     * v4.1 那版要求"邻居的 FACING == 朝向我的方向"，但原版中继器的 FACING 指的是
     * **输入**侧，于是两颗并排、各自朝前的原版中继器会被误判成互相锁定；
     * 一旦锁定就不再推进计时 —— 实机表现就是"中继器偶尔彻底不动"。
     */
    public static boolean isLocked(World world, BlockPos pos, IBlockState state) {
        EnumFacing facing = state.getValue(FACING);
        EnumFacing left = facing.rotateYCCW();
        EnumFacing right = facing.rotateY();
        return Math.max(powerOnSide(world, pos.offset(left), left),
                powerOnSide(world, pos.offset(right), right)) > 0;
    }

    /**
     * 邻居能给多少"锁定"信号（照原版 {@code getPowerOnSide} 的配对：side 是从我指向邻居）。
     */
    private static int powerOnSide(World world, BlockPos neighbor, EnumFacing side) {
        IBlockState s = world.getBlockState(neighbor);
        Block b = s.getBlock();
        if (b == Blocks.REDSTONE_BLOCK) {
            return 15;
        }
        if (b == Blocks.REDSTONE_WIRE) {
            return s.getValue(BlockRedstoneWire.POWER);
        }
        if (b == Blocks.POWERED_REPEATER || b == Blocks.POWERED_COMPARATOR
                || b instanceof BlockAdvancedRepeater) {
            return world.getStrongPower(neighbor, side);
        }
        return 0;
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
