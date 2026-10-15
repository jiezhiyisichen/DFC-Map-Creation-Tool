package com.jiezhimod.item;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 物品注册：1.12.2 走 RegistryEvent.Register&lt;Item&gt;。
 */
public class ModItems {

    public static final Item SPEED_COIL = new ItemSpeedCoil();
    public static final Item GRAVITY_COIL = new ItemGravityCoil();
    public static final Item HEALING_COIL = new ItemHealingCoil();

    /** 喷气背包（穿在胸甲槽） */
    public static final Item JETPACK = new ItemJetpack();
    /** 喷气开关（手持，按住右键起飞） */
    public static final Item JET_SWITCH = new ItemJetSwitch();
    /** DFC 遥控器（记录核心/稳定器坐标，再右键显示器完成绑定） */
    public static final Item DFC_REMOTE = new ItemDfcRemote();

    /** 模组专属物品页（1.12.2 没有内置页，必须自建；名字显示为 jiezhimod） */
    public static final CreativeTabs TAB = new CreativeTabs("jiezhimod") {
        @Override
        public ItemStack getTabIconItem() {
            return new ItemStack(ModItems.SPEED_COIL);
        }
    };

    static {
        // 放在静态块里：字段都已构造完成再挂页签，避免静态初始化顺序上的环
        SPEED_COIL.setCreativeTab(TAB);
        GRAVITY_COIL.setCreativeTab(TAB);
        HEALING_COIL.setCreativeTab(TAB);
        JETPACK.setCreativeTab(TAB);
        JET_SWITCH.setCreativeTab(TAB);
        DFC_REMOTE.setCreativeTab(TAB);
    }

    @SubscribeEvent
    public void onRegisterItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(SPEED_COIL);
        event.getRegistry().register(GRAVITY_COIL);
        event.getRegistry().register(HEALING_COIL);
        event.getRegistry().register(JETPACK);
        event.getRegistry().register(JET_SWITCH);
        event.getRegistry().register(DFC_REMOTE);
    }
}
