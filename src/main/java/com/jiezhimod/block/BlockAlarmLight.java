package com.jiezhimod.block;

import com.jiezhimod.alarm.AlarmConfig;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * 旋转警示灯（v4.5）—— 消防警报器那种：金属底座 + 内部旋转反光碗 + 外面一个圆灯罩。
 *
 * <h3>为什么方块本身几乎什么都不画</h3>
 * 真实外形（圆罩 / 旋转的灯芯 / 扫出去的光锥）全部由 {@code RenderAlarmLight} 现画 ——
 * <b>贴图画不出"完美的圆"</b>，16×16 的像素材质只会得到一个锯齿圈。
 * 所以这里只放一个占位 cube 模型（不给它贴图也没关系，TESR 会盖住），
 * 方块光给到 15 只是顺手让原版光照帮点忙；<b>主要的"照亮"效果全靠自绘光锥</b>，
 * 也就是说方块光关掉（=0）画面也不会塌。
 *
 * <h3>朝向</h3>
 * {@link #FACING} 有<b>六个方向</b>（不是只有水平四面）：贴天花板朝下、贴地面朝上、
 * 贴墙朝外，都按"点击那个面的外法线"来 —— 灯口永远朝外，底座永远贴着被点的那一面。
 *
 * <p>meta 用 4 bit：低 3 位放朝向序号，最高位放开关（正好用满）。</p>
 */
public class BlockAlarmLight extends Block {

    /** 灯口朝向（= 底座的反面） */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class);
    /** 是否亮着（同时决定原版方块光） */
    public static final PropertyBool ON = PropertyBool.create("on");

    // 底座那 0.35 厚的板子当碰撞箱；灯罩是圆的，不该让玩家按方块边界去撞
    private static final AxisAlignedBB BOX_UP = new AxisAlignedBB(0.1D, 0.0D, 0.1D, 0.9D, 0.35D, 0.9D);
    private static final AxisAlignedBB BOX_DOWN = new AxisAlignedBB(0.1D, 0.65D, 0.1D, 0.9D, 1.0D, 0.9D);
    private static final AxisAlignedBB BOX_NORTH = new AxisAlignedBB(0.1D, 0.1D, 0.65D, 0.9D, 0.9D, 1.0D);
    private static final AxisAlignedBB BOX_SOUTH = new AxisAlignedBB(0.1D, 0.1D, 0.0D, 0.9D, 0.9D, 0.35D);
    private static final AxisAlignedBB BOX_WEST = new AxisAlignedBB(0.65D, 0.1D, 0.1D, 1.0D, 0.9D, 0.9D);
    private static final AxisAlignedBB BOX_EAST = new AxisAlignedBB(0.0D, 0.1D, 0.1D, 0.35D, 0.9D, 0.9D);

    public BlockAlarmLight() {
        super(Material.IRON, MapColor.IRON);
        this.setRegistryName("jiezhimod", "alarm_light");
        this.setUnlocalizedName("alarm_light");
        this.setHardness(1.2F);
        this.setResistance(6.0F);
        this.setSoundType(SoundType.METAL);
        this.setHarvestLevel("pickaxe", 0);
        this.setCreativeTab(com.jiezhimod.item.ModItems.TAB);
        this.setLightLevel(0.0F);
        this.setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.UP)
                .withProperty(ON, Boolean.FALSE));
    }

    // ==================== 方块状态 ====================

    @Override
    protected BlockStateContainer createBlockState() {
        // ★ 属性名排序后是 facing < on，blockstates 的 variant key 必须照这个顺序写
        return new BlockStateContainer(this, new IProperty[]{FACING, ON});
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        EnumFacing[] all = EnumFacing.values();
        return this.getDefaultState()
                .withProperty(FACING, all[(meta & 7) % all.length])
                .withProperty(ON, Boolean.valueOf((meta & 8) != 0));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int m = state.getValue(FACING).ordinal() & 7;
        return state.getValue(ON) ? (m | 8) : m;
    }

    // ==================== 形状 / 渲染 ====================

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        switch (state.getValue(FACING)) {
            case DOWN:
                return BOX_DOWN;
            case NORTH:
                return BOX_NORTH;
            case SOUTH:
                return BOX_SOUTH;
            case WEST:
                return BOX_WEST;
            case EAST:
                return BOX_EAST;
            case UP:
            default:
                return BOX_UP;
        }
    }

    @Nullable
    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return this.getBoundingBox(state, world, pos);
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    /**
     * 世界里**完全不画方块本体**，外形全部交给 TEISR（{@code RenderAlarmLight}）。
     *
     * <p>为什么不像 HBM 那样留一个 cube 占位：那个 cube 会把灯罩连同光锥一起罩住，
     * 玩家看到的是"一个贴图方块"而不是"一盏灯"。设成 INVISIBLE 之后
     * 圆罩的轮廓、旋转的灯芯、扫出去的光锥才是唯一看得见的东西 ——
     * 这也是"完美的圆"能成立的前提（没有任何方块边界参与）。</p>
     *
     * <p>物品栏里仍然用 {@code models/item/alarm_light.json} 那个 cube 图标，
     * 两者互不影响。方块光 / 碰撞箱 / 破坏粒子都不受渲染类型影响。</p>
     */
    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.INVISIBLE;
    }

    @Override
    public int getLightValue(IBlockState state) {
        return state.getValue(ON) ? 15 : 0;
    }

    // ==================== 放置 ====================

    /**
     * 灯口朝"被点的那一面的外法线"：点天花板下表面 → 灯朝下；点墙 → 灯朝外。
     * 这样"朝任意方向摆放"不用另外加按键。
     */
    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing,
                                            float hitX, float hitY, float hitZ, int meta,
                                            EntityLivingBase placer, EnumHand hand) {
        return this.getDefaultState()
                .withProperty(FACING, facing)
                .withProperty(ON, Boolean.FALSE);
    }

    // ==================== 方块实体 ====================

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    /**
     * ★ 1.12.2 实际调的是这个（见 docs/踩坑记录 #17）。
     * 只有 {@code BlockContainer} 的默认实现里才会走 {@code createNewTileEntity}，
     * 这里直接继承 {@link Block}，两个方法只有一个存在，写错就永远不生效、并且零报错。
     */
    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileAlarmLight();
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable net.minecraft.world.World worldIn,
                               java.util.List<String> tooltip, net.minecraft.client.util.ITooltipFlag flag) {
        // ★ 写死四条字面量 key，不要用 "…" + i 拼接：
        //   自测哨兵是扫字符串字面量的，拼出来的 key 它看不见，
        //   下次改文案就会出现"语言文件里有、代码里没引用"的假死键。
        tooltip.add(net.minecraft.util.text.TextFormatting.GRAY
                + net.minecraft.client.resources.I18n.format("tooltip.jiezhimod.alarm_light.0"));
        tooltip.add(net.minecraft.util.text.TextFormatting.GRAY
                + net.minecraft.client.resources.I18n.format("tooltip.jiezhimod.alarm_light.1"));
        tooltip.add(net.minecraft.util.text.TextFormatting.AQUA
                + net.minecraft.client.resources.I18n.format("tooltip.jiezhimod.alarm_light.2"));
        tooltip.add(net.minecraft.util.text.TextFormatting.AQUA
                + net.minecraft.client.resources.I18n.format("tooltip.jiezhimod.alarm_light.3"));
        tooltip.add(net.minecraft.util.text.TextFormatting.LIGHT_PURPLE
                + net.minecraft.client.resources.I18n.format("tooltip.jiezhimod.alarm_light.4"));
    }

    // ==================== 染料快速上色 ====================

    /**
     * 手持染料右键 = 直接把这个颜色刷上去（不用开控制台）。
     *
     * <p>这是玩家最顺手的用法："我手里正好有红染料，往灯上一拍就该变红"。
     * 颜色写进方块实体，控制台下次下发会覆盖它 —— 语义是"临时改一下"，
     * 免得和"控制台统一管理"打架。</p>
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        ItemStack held = player.getHeldItem(hand);
        if (held.isEmpty() || !(held.getItem() instanceof net.minecraft.item.ItemDye)) {
            return false;
        }
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileAlarmLight)) {
            return false;
        }
        EnumDyeColor dye = EnumDyeColor.byDyeDamage(held.getMetadata());
        TileAlarmLight tile = (TileAlarmLight) te;
        // ★ 联动中的灯颜色由告警等级决定：这里直接改会被下一次等级变化覆盖，
        //   与其让玩家以为"染料坏了"，不如明说原因（且不消耗染料）。
        if (tile.isAuto()) {
            player.sendStatusMessage(new net.minecraft.util.text.TextComponentString(
                    net.minecraft.util.text.TextFormatting.AQUA + "[jiezhimod] "
                            + net.minecraft.util.text.TextFormatting.RESET
                            + net.minecraft.util.text.TextFormatting.YELLOW
                            + "这盏灯已联动显示屏，颜色由告警等级决定。用链接器重新绑控制台即可改回手动。"), true);
            return true;
        }
        AlarmConfig cfg = tile.config().withColor(dye.getColorValue()).withOn(true);
        tile.applyConfig(cfg);
        if (!player.capabilities.isCreativeMode) {
            held.shrink(1);
        }
        player.sendStatusMessage(new net.minecraft.util.text.TextComponentString(
                net.minecraft.util.text.TextFormatting.AQUA + "[jiezhimod] "
                        + net.minecraft.util.text.TextFormatting.RESET
                        + net.minecraft.util.text.TextFormatting.GREEN + "警示灯颜色已设为 "
                        + String.format(java.util.Locale.ROOT, "#%06X", dye.getColorValue())
                        + "（" + dye.getName() + "）"), true);
        return true;
    }
}
