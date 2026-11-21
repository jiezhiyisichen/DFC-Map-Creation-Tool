package com.jiezhimod.block;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 方块注册：1.12.2 走 RegistryEvent.Register&lt;Block&gt;，物品形态要另外注册 ItemBlock。
 *
 * 方块单独占一页创造模式物品栏：jiezhimod-红石（名字写在 lang 的 itemGroup.jiezhimod_redstone）。
 * 物品页是 ModItems.TAB（jiezhimod物品）。
 */
public class ModBlocks {

    /** 先建页签：匿名类里只在 getTabIconItem() 方法体内引用方块，静态初始化顺序安全 */
    public static final CreativeTabs TAB_REDSTONE = new CreativeTabs("jiezhimod_redstone") {
        @Override
        public ItemStack getTabIconItem() {
            // 3.9 时图标是密码盘；4.0 起密码盘搬去物品页，这一页留给高级红石中继器
            return new ItemStack(ModBlocks.ADVANCED_REPEATER);
        }
    };

    /** DFC 显示器单独一页（名字写在 lang 的 itemGroup.jiezhimod_screen） */
    public static final CreativeTabs TAB_SCREEN = new CreativeTabs("jiezhimod_screen") {
        @Override
        public ItemStack getTabIconItem() {
            return new ItemStack(ModBlocks.DFC_SCREEN);
        }
    };

    /** DFC 监控显示器（4x3x1 多方块，TESR 渲染） */
    public static final Block DFC_SCREEN = new BlockDfcScreen();
    public static final ItemBlock ITEM_DFC_SCREEN = new ItemBlock(DFC_SCREEN);
    /**
     * DFC 部件监视器（第二块显示器）。
     * 与 DFC_SCREEN 是同一台硬件、同一套多方块外壳，只是主界面画"部件页"：
     * 发射器 / 接收器 / 燃料 / 燃料喷射器 / 积蓄能量 / 能量抽取。
     * 复用同一批占位块与方块实体，见 {@link BlockDfcMonitor} 的说明。
     */
    public static final Block DFC_MONITOR = new BlockDfcMonitor();
    public static final ItemBlock ITEM_DFC_MONITOR = new ItemBlock(DFC_MONITOR);
    /**
     * DFC 自定义显示屏（第三块显示器）。
     * 与前两块共用外壳与多方块，但：
     *   - 右键打开 GUI（开机按钮在界面里），不是直接开关机；
     *   - 方块实体换成 {@link TileCustomScreen}，多存一份"最多 8 条"的数据槽配置；
     *   - 主界面画数据槽列表，内容由 DFC 遥控器蹲下右键接入。
     */
    public static final Block DFC_CUSTOM = new BlockCustomScreen();
    public static final ItemBlock ITEM_DFC_CUSTOM = new ItemBlock(DFC_CUSTOM);
    /**
     * DFC 曲线显示器（第四块显示器）。
     * 与前几块共用外壳与多方块，主界面画两张折线图（温度 / 发电量），
     * 历史采样全在客户端做，服务端不额外发一个字节 —— 见 {@link BlockDfcGraph}。
     */
    public static final Block DFC_GRAPH = new BlockDfcGraph();
    public static final ItemBlock ITEM_DFC_GRAPH = new ItemBlock(DFC_GRAPH);
    /**
     * DFC 标注显示器（第五块显示器，v4.0）。
     * 中央一台设备示意图，四周七条引线拉出「小标题 + 大数值」的标注块；
     * 每一格绑定哪个指标由玩家用调试棒逐格切换（见 {@link BlockDfcLabel}）。
     */
    public static final Block DFC_LABEL = new BlockDfcLabel();
    public static final ItemBlock ITEM_DFC_LABEL = new ItemBlock(DFC_LABEL);
    /**
     * DFC 激光状态显示器（第六块显示器，v4.2）。
     * 与自定义屏同一套槽位与 GUI：遥控器记录设备 → 放进槽位添加；
     * 每台设备占一个格子，画一台小激光器 + 五行「名称 数值 数据条」。
     */
    public static final Block DFC_LASER = new BlockDfcLaser();
    public static final ItemBlock ITEM_DFC_LASER = new ItemBlock(DFC_LASER);
    /**
     * 显示器的占位块（结构里除控制器之外的 11 格）。
     * 故意**不注册 ItemBlock**：玩家拿不到它，只能由放置显示器生成；
     * 破坏时统一掉显示器本体，避免"拆一格掉一堆零件"。
     */
    public static final Block DFC_SCREEN_PART = new BlockDfcScreenPart();

    /**
     * 高级红石中继器：外形与红石原理照搬原版中继器，
     * 延迟从"1~4 红石刻"放开到 1~1200 tick（0.05~60 秒），右键开界面调（tick / 秒两种单位）。
     */
    public static final Block ADVANCED_REPEATER = new BlockAdvancedRepeater();
    public static final ItemBlock ITEM_ADVANCED_REPEATER = new ItemBlock(ADVANCED_REPEATER);

    /**
     * 灯圈（v4.4）：一格一条发光的"圈"，相邻的圈自动连成一组。
     * 颜色走原版 tint 管线（光影包兼容），亮着时是光源，见 {@link BlockLightRing}。
     */
    public static final Block LIGHT_RING = new BlockLightRing();
    public static final ItemBlock ITEM_LIGHT_RING = new ItemBlock(LIGHT_RING);
    /** 灯圈控制台（v4.4）：红石触发，按界面配置的动作操作绑定的灯圈组 */
    public static final Block RING_CONSOLE = new BlockRingConsole();
    public static final ItemBlock ITEM_RING_CONSOLE = new ItemBlock(RING_CONSOLE);

    /**
     * 旋转警示灯（v4.5）：底座 + 旋转灯芯 + 圆灯罩，外形与光锥全部由 TESR 现画
     * （贴图画不出完美的圆），见 {@link BlockAlarmLight}。
     */
    public static final Block ALARM_LIGHT = new BlockAlarmLight();
    public static final ItemBlock ITEM_ALARM_LIGHT = new ItemBlock(ALARM_LIGHT);
    /** 警示灯控制台（v4.5）：配颜色 / 转速，红石上升沿把配置推给所有绑定的灯 */
    public static final Block ALARM_CONSOLE = new BlockAlarmConsole();
    public static final ItemBlock ITEM_ALARM_CONSOLE = new ItemBlock(ALARM_CONSOLE);

    /** 密码盘：占一格、高半格的触摸式数字键盘（右键按面板上的键，不开界面） */
    public static final Block KEYPAD = new BlockKeypad();
    public static final ItemBlock ITEM_KEYPAD = new ItemBlock(KEYPAD);
    /** 密码控制台：接收红石信号去操作密码盘，并在密码正确时输出红石信号 */
    public static final Block PASSWORD_CONSOLE = new BlockPasswordConsole();
    public static final ItemBlock ITEM_PASSWORD_CONSOLE = new ItemBlock(PASSWORD_CONSOLE);

    static {
        // 注册名必须显式跟方块一致，否则创造页拿到的物品没有模型
        ITEM_DFC_SCREEN.setRegistryName(DFC_SCREEN.getRegistryName());
        ITEM_DFC_SCREEN.setCreativeTab(TAB_SCREEN);
        ITEM_DFC_MONITOR.setRegistryName(DFC_MONITOR.getRegistryName());
        ITEM_DFC_MONITOR.setCreativeTab(TAB_SCREEN);
        ITEM_DFC_CUSTOM.setRegistryName(DFC_CUSTOM.getRegistryName());
        ITEM_DFC_CUSTOM.setCreativeTab(TAB_SCREEN);
        ITEM_DFC_GRAPH.setRegistryName(DFC_GRAPH.getRegistryName());
        ITEM_DFC_GRAPH.setCreativeTab(TAB_SCREEN);
        ITEM_DFC_LABEL.setRegistryName(DFC_LABEL.getRegistryName());
        ITEM_DFC_LABEL.setCreativeTab(TAB_SCREEN);
        ITEM_DFC_LASER.setRegistryName(DFC_LASER.getRegistryName());
        ITEM_DFC_LASER.setCreativeTab(TAB_SCREEN);
        ITEM_ADVANCED_REPEATER.setRegistryName(ADVANCED_REPEATER.getRegistryName());
        ITEM_ADVANCED_REPEATER.setCreativeTab(TAB_REDSTONE);
        ITEM_KEYPAD.setRegistryName(KEYPAD.getRegistryName());
        // 4.0：密码盘 / 密码控制台搬去「jiezhimod 物品」页，红石页腾出来给高级红石中继器
        ITEM_KEYPAD.setCreativeTab(com.jiezhimod.item.ModItems.TAB);
        ITEM_PASSWORD_CONSOLE.setRegistryName(PASSWORD_CONSOLE.getRegistryName());
        ITEM_PASSWORD_CONSOLE.setCreativeTab(com.jiezhimod.item.ModItems.TAB);
        // 灯圈 / 灯圈控制台放物品页（和密码系统、激光链接器同一页，都是"装置"）
        ITEM_LIGHT_RING.setRegistryName(LIGHT_RING.getRegistryName());
        ITEM_LIGHT_RING.setCreativeTab(com.jiezhimod.item.ModItems.TAB);
        ITEM_RING_CONSOLE.setRegistryName(RING_CONSOLE.getRegistryName());
        ITEM_RING_CONSOLE.setCreativeTab(com.jiezhimod.item.ModItems.TAB);
        ITEM_ALARM_LIGHT.setRegistryName(ALARM_LIGHT.getRegistryName());
        ITEM_ALARM_LIGHT.setCreativeTab(com.jiezhimod.item.ModItems.TAB);
        ITEM_ALARM_CONSOLE.setRegistryName(ALARM_CONSOLE.getRegistryName());
        ITEM_ALARM_CONSOLE.setCreativeTab(com.jiezhimod.item.ModItems.TAB);
    }

    @SubscribeEvent
    public void onRegisterBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().register(DFC_SCREEN);
        event.getRegistry().register(DFC_MONITOR);
        event.getRegistry().register(DFC_CUSTOM);
        event.getRegistry().register(DFC_GRAPH);
        event.getRegistry().register(DFC_LABEL);
        event.getRegistry().register(DFC_LASER);
        event.getRegistry().register(DFC_SCREEN_PART);
        event.getRegistry().register(ADVANCED_REPEATER);
        event.getRegistry().register(KEYPAD);
        event.getRegistry().register(PASSWORD_CONSOLE);
        event.getRegistry().register(LIGHT_RING);
        event.getRegistry().register(RING_CONSOLE);
        event.getRegistry().register(ALARM_LIGHT);
        event.getRegistry().register(ALARM_CONSOLE);
    }

    @SubscribeEvent
    public void onRegisterItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(ITEM_DFC_SCREEN);
        event.getRegistry().register(ITEM_DFC_MONITOR);
        event.getRegistry().register(ITEM_DFC_CUSTOM);
        event.getRegistry().register(ITEM_DFC_GRAPH);
        event.getRegistry().register(ITEM_DFC_LABEL);
        event.getRegistry().register(ITEM_DFC_LASER);
        event.getRegistry().register(ITEM_ADVANCED_REPEATER);
        event.getRegistry().register(ITEM_KEYPAD);
        event.getRegistry().register(ITEM_PASSWORD_CONSOLE);
        event.getRegistry().register(ITEM_LIGHT_RING);
        event.getRegistry().register(ITEM_RING_CONSOLE);
        event.getRegistry().register(ITEM_ALARM_LIGHT);
        event.getRegistry().register(ITEM_ALARM_CONSOLE);
    }
}
