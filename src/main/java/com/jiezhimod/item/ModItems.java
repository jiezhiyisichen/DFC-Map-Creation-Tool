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
    /** 密码连接器：把密码盘接进密码控制台 */
    public static final Item CONNECTOR = new ItemConnector();
    /**
     * 激光链接器：专门给激光状态显示器记设备用。
     *
     * 【为什么不让遥控器兼任】遥控器已经背着"核心屏绑定反应堆源"和
     * "自定义屏接外部设备"两套语义，再挂上激光屏就是同一个物品在三块屏上做三件不同的事，
     * 玩家必然记错（实机反馈"设置 SL 却把核心记进去了"正是这么来的）。
     */
    public static final Item LASER_CONNECTOR = new ItemLaserConnector();
    /** 灯圈链接器：蹲下右键灯圈记录 → 蹲下右键灯圈控制台绑定（v4.4） */
    public static final Item RING_CONNECTOR = new ItemRingConnector();
    /** 警示灯链接器（v4.5）：蹲下右键灯记录 → 蹲下右键控制台绑定 */
    public static final Item ALARM_CONNECTOR = new ItemAlarmConnector();
    /** 调试棒：蹲下右键任意本模组方块，打开调试界面（显示器可调刷新率） */
    public static final Item DEBUG_STICK = new ItemDebugStick();

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
        CONNECTOR.setCreativeTab(TAB);
        LASER_CONNECTOR.setCreativeTab(TAB);
        RING_CONNECTOR.setCreativeTab(TAB);
        ALARM_CONNECTOR.setCreativeTab(TAB);
        DEBUG_STICK.setCreativeTab(TAB);
    }

    @SubscribeEvent
    public void onRegisterItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(SPEED_COIL);
        event.getRegistry().register(GRAVITY_COIL);
        event.getRegistry().register(HEALING_COIL);
        event.getRegistry().register(JETPACK);
        event.getRegistry().register(JET_SWITCH);
        event.getRegistry().register(DFC_REMOTE);
        event.getRegistry().register(CONNECTOR);
        event.getRegistry().register(LASER_CONNECTOR);
        event.getRegistry().register(RING_CONNECTOR);
        event.getRegistry().register(ALARM_CONNECTOR);
        event.getRegistry().register(DEBUG_STICK);
    }
}
