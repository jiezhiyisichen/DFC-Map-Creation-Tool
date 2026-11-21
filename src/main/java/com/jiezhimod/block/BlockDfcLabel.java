package com.jiezhimod.block;

import com.jiezhimod.dfc.LabelLayout;
import com.jiezhimod.dfc.ScreenStructure;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * DFC **标注显示器** —— 第五块显示器，和 {@link BlockDfcScreen} 是同一台硬件。
 *
 * 【它和另外四块的区别】
 *   主界面画 {@link com.jiezhimod.dfc.ScreenLayout#PAGE_LABEL}：
 *   中央一台设备示意图，四周七条引线拉出「小标题 + 大数值」的标注块。
 *   相比核心页的三栏仪表，这一页不需要玩家记住"左栏是什么、右栏是什么" ——
 *   每个数字都贴着自己的名字。
 *
 * 【为什么要换方块实体】七个格子各自绑哪个指标是**玩家配置**，
 * 要存 NBT、要能被服务器广播，所以从 {@link TileDfcScreen} 派生出
 * {@link TileLabelScreen} 专门放这七个 int。其它四块屏共用基类的那份。
 *
 * 【右键仍然是开关机】配置走调试棒（蹲下右键），
 * 与刷新率、密码盘便签文字是同一个入口，不额外抢右键。
 */
public class BlockDfcLabel extends BlockDfcScreen {

    public BlockDfcLabel() {
        // 注册名只能在父类构造期一次定下（二次 setRegistryName 会抛异常 = 加载即崩）
        super("dfc_label", "dfc_label");
    }

    /**
     * ★★ 必须是 `createTileEntity`（Forge 实际调用的那一个）。
     *
     * <p>【同一个坑踩了两次】父类 {@link BlockDfcScreen} 覆写了
     * {@code createTileEntity} 并直接返回 {@link TileDfcScreen}；这里原来只覆写了
     * {@code createNewTileEntity} —— 那是 `BlockContainer` 默认实现内部才调的，
     * 父类已经把入口换掉了，所以它**永远不会被调用**。
     * 结果：每一块标注显示器背后都是 `TileDfcScreen`，7 个指标配置根本没被读写。</p>
     */
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileLabelScreen();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileLabelScreen();
    }

    /** 拆掉 / 放不下时返还的是"标注显示器"本身，不能变成一块核心屏 */
    @Override
    protected Item displayItem() {
        return ModBlocks.ITEM_DFC_LABEL;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.dfc_label.0",
                ScreenStructure.PARTS));
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.dfc_label.1"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_label.2",
                LabelLayout.SLOTS));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_label.3"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.dfc_label.4"));
    }
}
