package com.jiezhimod.item;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 喷气背包（Jetpack）—— 装备本体，玩家穿在**胸甲槽**。
 *
 * 【为什么用 ArmorItem 而不是普通 Item】
 * 需求是"玩家穿戴装备后…"，必须占用一个真实装备槽才能被原版存档、
 * 附魔、死亡掉落等机制正确处理。ItemArmor 提供了整套装备槽语义，
 * 这里把材质设成 AIR（不消耗护甲值、不提供护甲保护），
 * 只借用它的"能穿在胸甲槽"这一件事。
 *
 * 实际推力由 {@link JetpackEffect} 在服务端每 tick 施加，本类只负责物品本身。
 */
public class ItemJetpack extends ItemArmor {

    /**
     * 装备材质槽位：[0]= boots, [1]= leggings, [2]= chestplate, [3]= helmet。
     * 取 2 = 胸甲槽。
     */
    public ItemJetpack() {
        super(ArmorMaterial.IRON, 0, EntityEquipmentSlot.CHEST);
        this.setRegistryName("jiezhimod", "jetpack");
        this.setUnlocalizedName("jetpack");
        this.setMaxStackSize(1);
    }

    /**
     * 1.12.2 签名是 (ItemStack, World, List, ITooltipFlag)，且 ITooltipFlag 属客户端类，
     * 必须 @SideOnly —— 与现有三个线圈保持一致。
     */
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(TextFormatting.AQUA + "Jetpack");
        tooltip.add(TextFormatting.GRAY + "Wear in the chest slot, then hold "
                + TextFormatting.YELLOW + "Jet Switch"
                + TextFormatting.GRAY + " and right-click to fly");
        tooltip.add(TextFormatting.GRAY + "Burn time: "
                + TextFormatting.YELLOW + (int) JetHeat.MAX_BURN_SECONDS + "s"
                + TextFormatting.GRAY + " per full tank");
        tooltip.add(TextFormatting.GRAY + "Release right-click to cool down");
        tooltip.add(TextFormatting.DARK_GRAY + "Overheats when the tank runs dry");
    }
}
