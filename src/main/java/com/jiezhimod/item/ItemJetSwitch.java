package com.jiezhimod.item;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 喷气开关（Jet Switch）—— 手持物，配合已穿戴的喷气背包使用。
 *
 * 交互：按住右键起飞，松开立刻失去动力（喷气粒子随之停止）。
 * 本类只做"能被识别为开关"这一件事，飞行逻辑全在 {@link JetpackEffect}（服务端权威）。
 * 热量条由 {@link JetHeatBarRenderer} 在客户端渲染。
 */
public class ItemJetSwitch extends Item {

    public ItemJetSwitch() {
        this.setRegistryName("jiezhimod", "jet_switch");
        this.setUnlocalizedName("jet_switch");
        this.setMaxStackSize(1);
        // 开关不参与耐久/燃烧等 vanilla 消耗机制
        this.setMaxDamage(0);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(TextFormatting.AQUA + "Jet Switch");
        tooltip.add(TextFormatting.GRAY + "Requires a "
                + TextFormatting.YELLOW + "Jetpack"
                + TextFormatting.GRAY + " in the chest slot");
        tooltip.add(TextFormatting.GRAY + "Hold right-click: fly"
                + TextFormatting.DARK_GRAY + "  /  release: stop");
        tooltip.add(TextFormatting.GRAY + "The heat bar is drawn on this item while flying");
    }
}
