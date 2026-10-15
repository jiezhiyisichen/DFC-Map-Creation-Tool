package com.jiezhimod.item;

import net.minecraft.item.Item;

/**
 * 线圈物品公共基类（加速 / 重力 / 治愈三种线圈共用）。
 *
 * 共同约定：不可堆叠、注册名固定为 jiezhimod:&lt;name&gt;。三种线圈的效果都由
 * PlayerTickEvent / LivingJumpEvent 里直接改物理量或生命值实现，**不挂药水效果**，
 * 因此可以和玩家自己喝的药水叠加而不互相覆盖。
 */
public abstract class ItemCoil extends Item {

    protected ItemCoil(String name) {
        // 显式两参：单参形式靠「当前活跃 mod 容器」补前缀，在非加载环境（自测/工具）
        // 会退化成 minecraft:<name>。写死 modid 才能保证永远是 jiezhimod:<name>。
        this.setRegistryName("jiezhimod", name);
        this.setUnlocalizedName(name);
        this.setMaxStackSize(1);
    }
}
