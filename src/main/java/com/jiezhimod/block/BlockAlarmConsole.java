package com.jiezhimod.block;

import com.jiezhimod.JiezhiMod;
import com.jiezhimod.proxy.CommonProxy;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * 旋转警示灯控制台（v4.5）：占一格，右键打开配置界面。
 *
 * <p>界面里只有三样东西：<b>颜色</b>（RGB 手填 / 预设 / 十六个染料色）、
 * <b>转速</b>（rpm）、<b>开关</b>。改完立刻推给所有绑定的灯 ——
 * 不给"应用"按钮（和灯圈控制台保持一致，玩家明确要求过"输入即保存"）。</p>
 *
 * <p>绑定用 {@link com.jiezhimod.item.ItemAlarmConnector}：
 * 蹲下右键一盏灯记录，再蹲下右键本控制台加入成员表。</p>
 */
public class BlockAlarmConsole extends BlockContainer {

    public BlockAlarmConsole() {
        super(Material.IRON, MapColor.BLACK);
        this.setRegistryName("jiezhimod", "alarm_console");
        this.setUnlocalizedName("alarm_console");
        this.setHardness(2.5F);
        this.setResistance(15.0F);
        this.setSoundType(SoundType.METAL);
        this.setHarvestLevel("pickaxe", 0);
        this.setCreativeTab(com.jiezhimod.item.ModItems.TAB);
    }

    /**
     * {@link BlockContainer} 默认 INVISIBLE（客户端整体跳过渲染）。
     * 这块方块用普通 cube 模型，必须显式改回 MODEL，否则是个"能透视的洞"。
     */
    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileAlarmConsole();
    }

    /**
     * ★ 两个都覆写：1.12.2 实际调的是 {@code createTileEntity(World, IBlockState)}，
     * 而 {@link BlockContainer} 的默认实现里才会走 {@code createNewTileEntity}。
     * 只写一个在某些继承链下会静默失效（见 docs/踩坑记录 #17）。
     */
    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileAlarmConsole();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        // 手里拿着警示灯链接器且蹲下 = 绑定动作，把事件让给物品（不蹲 = 开界面）
        if (player.getHeldItem(hand).getItem() instanceof com.jiezhimod.item.ItemAlarmConnector
                && player.isSneaking()) {
            return false;
        }
        if (world.isRemote) {
            return true;
        }
        player.openGui(JiezhiMod.instance, CommonProxy.GUI_ALARM_CONSOLE, world,
                pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    @Override
    public void addInformation(net.minecraft.item.ItemStack stack, @Nullable World worldIn,
                               java.util.List<String> tooltip, net.minecraft.client.util.ITooltipFlag flag) {
        tooltip.add(net.minecraft.util.text.TextFormatting.GRAY
                + net.minecraft.client.resources.I18n.format("tooltip.jiezhimod.alarm_console.0"));
        tooltip.add(net.minecraft.util.text.TextFormatting.AQUA
                + net.minecraft.client.resources.I18n.format("tooltip.jiezhimod.alarm_console.1"));
        tooltip.add(net.minecraft.util.text.TextFormatting.AQUA
                + net.minecraft.client.resources.I18n.format("tooltip.jiezhimod.alarm_console.2"));
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        // 控制台被拆：把管着的灯全部关掉，否则它们会一直按最后一份配置转下去
        TileEntity te = world.getTileEntity(pos);
        if (!world.isRemote && te instanceof TileAlarmConsole) {
            TileAlarmConsole console = (TileAlarmConsole) te;
            for (BlockPos p : console.members()) {
                TileEntity lamp = world.getTileEntity(p);
                if (lamp instanceof TileAlarmLight) {
                    ((TileAlarmLight) lamp).turnOff();
                }
            }
        }
        super.breakBlock(world, pos, state);
    }
}
