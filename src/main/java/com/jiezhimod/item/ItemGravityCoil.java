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
 * 重力线圈（Gravity Coil）——模仿 Roblox 的 Gravity Coil：手持时跳得更高。
 *
 * 刻意不用「跳跃提升」药水效果，而是直接改跳跃初速度，见 {@link GravityCoilEffect}。
 */
public class ItemGravityCoil extends ItemCoil {

    public ItemGravityCoil() {
        super("gravity_coil");
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(TextFormatting.BLUE + I18n.format("tooltip.jiezhimod.coil_gravity.0"));
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.coil_gravity.1",
                Math.round(GravityCoilEffect.coilJumpHeight())));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.coil_gravity.2"));
    }
}
