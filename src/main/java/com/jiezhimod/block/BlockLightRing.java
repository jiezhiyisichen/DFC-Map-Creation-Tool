package com.jiezhimod.block;

import com.jiezhimod.ring.RingGroupManager;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * 灯圈方块（v4.4）—— **整格方块**，长得像红石灯（一块会自己发光的方灯），
 * 相邻的灯圈自动连成一组，颜色任意 RGB。
 *
 * <h3>为什么是整格（v4.4 按实机反馈改过一次）</h3>
 * 第一版做成了"3px 细梁拼的方框"，实机一看不对：多个灯圈拼成环形时，
 * 每个格子只占细框那么点体积，中间是空的、脚下是空的，跟"灯圈是一格格搭出来的"完全不符。
 * 现在改成 0~16 的完整立方体（{@code isFullCube}/{@code isOpaqueCube} 都是 true），
 * 贴图是一块深底 + 一圈发光的方环（{@link #getLightValue} 亮时 15），
 * 几十个排成圆，视觉上就是一环环的灯带 —— 和参考视频里激光炮身上那种一致。
 *
 * <h3>单 / 多方块：激活一个 = 激活整组</h3>
 * 六面相邻的灯圈属于同一组（洪水填充，见 {@link RingGroupManager}）。
 * 红石激活其中任何一格，整组一起亮；组的颜色 / 淡入淡出 / 闪烁由
 * {@link BlockRingConsole 灯圈控制台} 下发。
 *
 * <h3>"着色器"是怎么做的（重要，别改成自写 GLSL）</h3>
 * 颜色走 **原版 tint 管线**：模型六个面都标了 {@code tintindex=0}，
 * 客户端在 {@link com.jiezhimod.client.ring.RingColorHandlers} 里注册 {@code IBlockColor}
 * （必须挂在 {@code ColorHandlerEvent} 上，见那个类的说明），按方块实体的状态算出 RGB。
 * 选这条路的理由：
 * <ul>
 *   <li>OptiFine / Iris / 光影包都**尊重方块颜色乘数** —— 自写 GLSL 会被光影整体接管，
 *       到时候"装了光影就变白"，反而废掉；</li>
 *   <li>发光 = {@link #getLightValue}（返回 {@link #LEVEL} 0~15 档）：方块自己全亮，
 *       周围一圈也被照亮；淡入淡出 / 闪烁都是逐档变化，光照跟着一起走。
 *       【注意】1.12 原版的方块光**没有颜色通道**，"周围地面被染成灯的颜色"只有
 *       OptiFine Colored Lights / 光影包能给，mod 侧没有接口 —— 别在文档里承诺这个；</li>
 *   <li>淡入淡出的颜色是客户端按参数自己算的（{@code RingState}），不用逐刻发包。</li>
 * </ul>
 * 之后的旋转警报灯也走这一套（tint + 发光 + 状态广播），这里就是它的底座。
 */
public class BlockLightRing extends Block {

    /**
     * 亮度档位 0~15（0 = 灭）。
     *
     * <h3>为什么是 0~15 而不是"开/关"两态</h3>
     * 淡入淡出要"越来越亮"这个过程，只有两态就只能是硬切。
     * 而且 {@code tint} 只在区块重烘焙时采样 —— 光靠 tint 表达亮度，
     * 区块不重建画面就不动（v4.4 实机"淡入淡出/闪烁没效果"的根因）。
     * 把亮度放进**方块状态**以后，档位一变就会触发重烘焙 + 光照重算，
     * 灯自己亮度和周围光照同时跟上，肉眼一定看得见。
     *
     * <p>meta 只有 4 bit，这里正好用满 0~15 —— 灯圈没有别的状态要存。</p>
     */
    public static final PropertyInteger LEVEL = PropertyInteger.create("level", 0, 15);

    public BlockLightRing() {
        // 照红石灯的材质（Material.REDSTONE_LIGHT：活塞推不动、不挡光），
        // 亮度 / 颜色仍然由状态与 tint 决定
        super(Material.REDSTONE_LIGHT, MapColor.AIR);
        this.setRegistryName("jiezhimod", "light_ring");
        this.setUnlocalizedName("light_ring");
        this.setHardness(0.3F);
        this.setResistance(1.5F);
        this.setSoundType(SoundType.GLASS);
        // 光照值随 LEVEL 档位变，所以这里只给个占位，
        // 真正的值在 getLightValue(IBlockState) 里按状态返回
        this.setLightLevel(0.0F);
        this.setCreativeTab(com.jiezhimod.item.ModItems.TAB);
        this.setDefaultState(this.blockState.getBaseState()
                .withProperty(LEVEL, Integer.valueOf(0)));
    }

    // ==================== 方块状态 ====================

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, new IProperty[]{LEVEL});
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return this.getDefaultState().withProperty(LEVEL, Integer.valueOf(meta & 15));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(LEVEL);
    }

    // ==================== 形状 / 渲染 ====================

    /** 碰撞 / 轮廓按整格算：嵌进墙体里时不会露出缝，也不会挡不住人 */
    private static final AxisAlignedBB FULL = new AxisAlignedBB(0, 0, 0, 1, 1, 1);

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return FULL;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return true;
    }

    /** 整格方块（v4.4 从"3px 细框"改成"红石灯式整块"）：不透明，六个面按常规剔除 */
    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return true;
    }

    /**
     * 透光率 0：灯圈**不挡光**（而且它自己发光，光要能顺着往邻居那边推，
     * 邻居拿到的就是 15 而不是 14，整条灯带看起来才"亮成一片"）。
     * 注意这跟"整格不透明"不冲突：{@link #isOpaqueCube} 管的是面剔除，这里管的是光照。
     */
    @Override
    public int getLightOpacity(IBlockState state) {
        return 0;
    }

    // 【渲染层不再覆写】整格 + 不透明贴图走默认的 SOLID；
    // 顺带也没有"服务端加载时引用客户端专属类 BlockRenderLayer"的隐患了。

    /** 亮着 = 光源（方块自己全亮，周围一圈也被照亮；光影包在这层做彩色环境光适配） */
    @Override
    public int getLightValue(IBlockState state) {
        return state.getValue(LEVEL);
    }

    // ==================== 方块实体 ====================

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileLightRing();
    }

    // ==================== 连组 ====================

    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        super.onBlockAdded(world, pos, state);
        if (!world.isRemote) {
            // 新圈放下来立刻入组（并继承组当前的颜色 / 亮灭）
            RingGroupManager.of(world).rebuild(world, pos);
        }
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        super.breakBlock(world, pos, state);
        if (!world.isRemote) {
            // 拆掉一格后，邻居的组要重算（可能把一组拆成两半）
            RingGroupManager.of(world).rebuild(world, pos);
        }
    }

    @Override
    public void neighborChanged(IBlockState state, World world, BlockPos pos, Block block,
                                BlockPos fromPos) {
        if (world.isRemote) {
            return;
        }
        // 自己改 LEVEL 时会把 6 个邻居的 neighborChanged 全叫一遍；
        // 那不改成员关系，重建组纯属白烧 CPU（见 RingGroupManager#beginQuiet）。
        if (RingGroupManager.quiet()) {
            return;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileLightRing)) {
            return;
        }
        TileLightRing tile = (TileLightRing) te;
        // 红石电平变了才考虑重建/下发 —— 附近的红石粉闪烁不该让整组洪水填充刷屏
        boolean redstoneChanged = tile.onRedstoneInput(world.isBlockIndirectlyGettingPowered(pos) > 0);
        if (redstoneChanged || world.getBlockState(fromPos).getBlock() instanceof BlockLightRing) {
            RingGroupManager.of(world).rebuild(world, pos);
        }
    }

    /**
     * 红石直连：给任意一格灯圈红石，整组亮；断开红石整组灭。
     *
     * <p>【为什么放这里而不是控制台】需求是"红石激活一个灯圈方块，其他的连在一起的同样激活"
     * —— 也就是灯圈**自己**也能吃红石，控制台只是高级玩法（换色 / 淡入淡出 / 闪烁）。
     * 两者共存：控制台正在闪烁时红石不再插手（闪烁由控制台的周期说了算，
     * 红石一插手闪烁节奏就被打乱，玩家会以为坏了）。</p>
     *
     * <p>具体实现在 {@link TileLightRing#onRedstoneInput(boolean)}：
     * 上升沿 / 下降沿各改一次组状态，然后**广播**给客户端
     * （运行期必须自己发包，见 {@code MessageRingState}）。</p>
     */
}
