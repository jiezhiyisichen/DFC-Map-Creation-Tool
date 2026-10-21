package com.jiezhimod.block;

import com.jiezhimod.dfc.GraphHistory;
import com.jiezhimod.dfc.ScreenStructure;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * DFC **曲线显示器** —— 第四块显示器，和 {@link BlockDfcScreen} 是同一台硬件。
 *
 * 【和另外三块屏的差别】
 *   主界面画 {@link com.jiezhimod.dfc.ScreenLayout#PAGE_GRAPH}：
 *   并排两张折线图 —— 左边温度、右边发电量，纵轴自动量程
 *   （初始 0~100，读数超了就抬到把最高点包进去）。
 *
 * 【为什么不换方块实体】历史采样完全在**客户端**做：
 * 每台屏的 {@code ScreenCanvas} 自己维护一份 {@link GraphHistory}，
 * 服务端照旧只发当前读数。这样曲线上一个字节的网络流量都不多花，
 * 而且四块屏各自有各自的曲线，互不干扰。
 *
 * 【为什么右键还是开关机】它的"内容"是自动累积的曲线，没有需要编排的东西，
 * 所以沿用手感最简单的右键开关机（自定义屏那种开 GUI 的手法在这里是多余的）。
 * 见 {@link ScreenMultiblock#handleUse}：不是 TileCustomScreen 就落到 togglePower。
 */
public class BlockDfcGraph extends BlockDfcScreen {

    public BlockDfcGraph() {
        // 注册名交给父类构造期一次性设定（IForgeRegistryEntry 不允许二次 setRegistryName）
        super("dfc_graph", "dfc_graph");
    }

    /** 拆掉 / 放不下时返还的是"曲线显示器"本身，不能变成一块核心屏 */
    @Override
    protected Item displayItem() {
        return ModBlocks.ITEM_DFC_GRAPH;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.dfc_graph.0",
                ScreenStructure.PARTS));
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.dfc_graph.1"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_graph.2"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_graph.3"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.dfc_graph.4"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.dfc_graph.5",
                GraphHistory.CAPACITY * GraphHistory.SAMPLE_MS / 1000));
    }
}
