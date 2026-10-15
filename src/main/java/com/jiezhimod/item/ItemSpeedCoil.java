package com.jiezhimod.item;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 加速线圈（Speed Coil）——模仿 Roblox 的 Speed Coil：手持时持续加速。
 *
 * 实现思路（刻意不用迅捷效果）：EntityPlayer.getAIMoveSpeed() 读的就是
 * MOVEMENT_SPEED 属性，所以直接给该属性挂一个 MULTIPLY_TOTAL 修饰符就能得到
 * 干净、可叠加、无粒子无 HUD 图标的倍率加速，见 {@link SpeedCoilEffect}。
 */
public class ItemSpeedCoil extends ItemCoil {

    public ItemSpeedCoil() {
        super("speed_coil");
    }

    /**
     * 1.12.2 的签名是 (ItemStack, World, List, ITooltipFlag)（不是 1.11 时代的
     * EntityPlayer + boolean），且第四参 ITooltipFlag 属客户端类，必须 @SideOnly。
     */
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(TextFormatting.RED + "Speed Coil");
        tooltip.add(TextFormatting.GRAY + "Hold in either hand: "
                + TextFormatting.YELLOW + "x" + (int) SpeedCoilEffect.MULTIPLIER
                + TextFormatting.GRAY + " movement speed");
        tooltip.add(TextFormatting.DARK_GRAY + "Raw attribute boost, not a potion effect");
    }
}
