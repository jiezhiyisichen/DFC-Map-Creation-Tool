package com.jiezhimod.item;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 治愈线圈（Healing Coil）——模仿 Roblox 的 Healing Coil：手持时持续回血。
 *
 * 刻意不用「生命恢复」药水效果，而是直接调用 heal()，见 {@link HealingCoilEffect}。
 */
public class ItemHealingCoil extends ItemCoil {

    public ItemHealingCoil() {
        super("healing_coil");
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(TextFormatting.GREEN + I18n.format("tooltip.jiezhimod.coil_healing.0"));
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.coil_healing.1",
                (int) HealingCoilEffect.HEAL_PER_SECOND));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.coil_healing.2"));
    }
}
