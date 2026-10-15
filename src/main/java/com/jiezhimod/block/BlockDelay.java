package com.jiezhimod.block;

import com.jiezhimod.JiezhiMod;
import com.jiezhimod.proxy.CommonProxy;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
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
 * 红石延时器：把红石信号整体延后一段时间再输出。
 *
 * 摆放不挑方向：信号从任意一面进来，输出方向以"进来的那一面"为基准
 * （直通 / 左 / 右 / 三向），放下即可用，不用像中继器那样对着方向摆。
 *
 * 右键打开 GUI 设置"延时时间（tick / 秒 / 分）"与"输出方向"。
 * 内部逻辑在 {@link TileDelay}：输入电平一变就排队，到点翻转输出，两端都延时。
 */
public class BlockDelay extends BlockContainer {

    /** 是否正在输出（只影响贴图亮暗，真实状态在 TileDelay 里） */
    public static final PropertyBool POWERED = PropertyBool.create("powered");

    public BlockDelay() {
        super(Material.ROCK);
        // 显式两参：单参形式靠"当前活跃 mod 容器"补前缀，非加载环境会退化成 minecraft:
        this.setRegistryName("jiezhimod", "delay_block");
        this.setUnlocalizedName("delay_block");
        this.setHardness(1.2F);
        this.setResistance(8.0F);
        this.setSoundType(SoundType.STONE);
        this.setHarvestLevel("pickaxe", 0);
        this.setCreativeTab(ModBlocks.TAB_REDSTONE);
        this.setDefaultState(this.blockState.getBaseState()
                .withProperty(POWERED, Boolean.FALSE));
    }

    // ==================== 渲染 ====================

    /**
     * 【关键】BlockContainer.getRenderType() 原版返回 {@code EnumBlockRenderType.INVISIBLE} ——
     * 那是给"纯方块实体占位方块"（基岩、空气等）用的，客户端会**整体跳过渲染**。
     * 不覆盖的话方块会透明、能透视，看起来就像在地上挖了个洞。
     * 必须显式返回 MODEL 才会走正常的方块模型渲染。
     */
    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    // ==================== 方块状态 ====================

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, POWERED);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(POWERED) ? 1 : 0;
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return this.getDefaultState().withProperty(POWERED, Boolean.valueOf((meta & 1) != 0));
    }

    // ==================== 方块实体 ====================

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileDelay();
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state,
                                    EntityPlayer playerIn, EnumHand hand, EnumFacing facing,
                                    float hitX, float hitY, float hitZ) {
        if (worldIn.isRemote) {
            return true;
        }
        TileEntity te = worldIn.getTileEntity(pos);
        if (te instanceof TileDelay) {
            // 先推一次当前配置，再开界面，避免玩家看到上一次的旧数值
            ((TileDelay) te).syncTo(playerIn);
        }
        playerIn.openGui(JiezhiMod.instance, CommonProxy.GUI_DELAY, worldIn,
                pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    // ==================== 红石 ====================

    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    /**
     * side 的原版语义：从"请求方"指向"本方块"的方向。
     * 所以真正被供能的邻居在 pos.offset(side.getOpposite())。
     */
    @Override
    public int getWeakPower(IBlockState state, IBlockAccess access, BlockPos pos, EnumFacing side) {
        TileEntity te = access.getTileEntity(pos);
        if (!(te instanceof TileDelay)) {
            return 0;
        }
        TileDelay tile = (TileDelay) te;
        if (!tile.isOutputOn()) {
            return 0;
        }
        return DelayLogic.isOutputSide(tile.getInputDir(), tile.getMode(), side.getOpposite())
                ? 15 : 0;
    }

    //【故意不覆写 getStrongPower】
    // 强功率会隔着实心方块把信号传出去，等于给自激开了第二条通路：
    // 自己的输出穿过方块绕回来，再次被自己当成输入。
    // 中继器本身也只提供弱功率，这里与它保持一致。

    // ==================== 生命周期 ====================

    @Override
    public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state) {
        worldIn.notifyNeighborsOfStateChange(pos, this, true);
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        super.breakBlock(worldIn, pos, state);
        worldIn.notifyNeighborsOfStateChange(pos, this, true);
    }
}
