package com.jiezhimod.block;

import com.jiezhimod.dfc.DfcWatch;
import com.jiezhimod.dfc.ScreenStructure;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/**
 * DFC **自定义显示屏** —— 第三块显示器，和 {@link BlockDfcScreen} 同一台硬件。
 *
 * 【和另外两块屏的两点差别】
 *   1. **右键打开 GUI**（不是像另外两块那样开关机）：开机按钮改到 GUI 里，
 *      因为这块屏的"内容"本身就得在界面上编排 —— 8 个数据槽要能逐条删除；
 *   2. 主界面画 {@link com.jiezhimod.dfc.ScreenLayout#PAGE_CUSTOM}：
 *      一张最多 8 行的数据列表，每行是一台外部设备（储罐 / 电池）的一个读数。
 *
 * 【数据怎么进来】用 DFC 遥控器：**蹲下右键设备**记录，再**蹲下右键这块屏**接入。
 * 详见 {@link com.jiezhimod.item.ItemDfcRemote}。
 *
 * 【方块实体要换】前两块屏共用 {@link TileDfcScreen}，这一块必须换成
 * {@link TileCustomScreen}（它多存一份 8 槽配置），所以这里覆写 createTileEntity。
 */
public class BlockCustomScreen extends BlockDfcScreen {

    public BlockCustomScreen() {
        // 注册名交给父类构造期一次性设定（IForgeRegistryEntry 不允许二次 setRegistryName）
        super("dfc_custom", "dfc_custom");
    }

    /** 拆掉 / 放不下时返还的是"自定义显示屏"本身 */
    @Override
    protected Item displayItem() {
        return ModBlocks.ITEM_DFC_CUSTOM;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileCustomScreen();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileCustomScreen();
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + "Occupies 4 wide x 3 tall = " + ScreenStructure.PARTS
                + " blocks (ceiling-mounted)");
        tooltip.add(TextFormatting.GRAY + "Place against the underside of a ceiling to hang it overhead");
        tooltip.add(TextFormatting.AQUA + "Right-click: open the panel (power + data list)");
        tooltip.add(TextFormatting.AQUA + "Shows up to " + DfcWatch.MAX
                + " readings from tanks / batteries");
        tooltip.add(TextFormatting.GRAY + "Sneak + right-click a tank/battery with the DFC remote to record it,");
        tooltip.add(TextFormatting.GRAY + "then sneak + right-click this display to hook it up");
    }
}
