package com.jiezhimod.item;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * 线圈通用判定：主手或副手拿着指定线圈即视为「装备中」。
 *
 * 单独抽出来是因为三种线圈（加速/重力/治愈）都要做同一个判断，
 * 且该判断同时被服务端逻辑与离线自测引用。
 */
public final class CoilUtil {

    private CoilUtil() {
    }

    /** 主手或副手任一拿着指定线圈 */
    public static boolean isHolding(EntityPlayer player, Item coil) {
        return isCoil(player.getHeldItemMainhand(), coil) || isCoil(player.getHeldItemOffhand(), coil);
    }

    /** 单个 ItemStack 是否是指定线圈（空栈、null 均安全） */
    public static boolean isCoil(ItemStack stack, Item coil) {
        return stack != null && !stack.isEmpty() && stack.getItem() == coil;
    }
}
