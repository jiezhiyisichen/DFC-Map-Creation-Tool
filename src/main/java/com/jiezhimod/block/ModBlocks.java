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

    /** 先建页签：匿名类里只在 getTabIconItem() 方法体内引用 DELAY_BLOCK，静态初始化顺序安全 */
    public static final CreativeTabs TAB_REDSTONE = new CreativeTabs("jiezhimod_redstone") {
        @Override
        public ItemStack getTabIconItem() {
            return new ItemStack(ModBlocks.DELAY_BLOCK);
        }
    };

    /** DFC 显示器单独一页（名字写在 lang 的 itemGroup.jiezhimod_screen） */
    public static final CreativeTabs TAB_SCREEN = new CreativeTabs("jiezhimod_screen") {
        @Override
        public ItemStack getTabIconItem() {
            return new ItemStack(ModBlocks.DFC_SCREEN);
        }
    };

    public static final Block DELAY_BLOCK = new BlockDelay();

    /** 物品形态（ItemBlock），在 Register&lt;Item&gt; 里注册 */
    public static final ItemBlock ITEM_DELAY_BLOCK = new ItemBlock(DELAY_BLOCK);

    /** DFC 监控显示器（4x3x1 多方块，TESR 渲染） */
    public static final Block DFC_SCREEN = new BlockDfcScreen();
    public static final ItemBlock ITEM_DFC_SCREEN = new ItemBlock(DFC_SCREEN);
    /**
     * DFC 部件监视器（第二块显示器）。
     * 与 DFC_SCREEN 是同一台硬件、同一套多方块外壳，只是主界面画"部件页"：
     * 发射器 / 接收器 / 燃料 / 燃料喷射器 / 积蓄能量 / 能量爆发。
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
     * 显示器的占位块（结构里除控制器之外的 11 格）。
     * 故意**不注册 ItemBlock**：玩家拿不到它，只能由放置显示器生成；
     * 破坏时统一掉显示器本体，避免"拆一格掉一堆零件"。
     */
    public static final Block DFC_SCREEN_PART = new BlockDfcScreenPart();

    static {
        // 注册名必须显式跟方块一致，否则创造页拿到的物品没有模型
        ITEM_DELAY_BLOCK.setRegistryName(DELAY_BLOCK.getRegistryName());
        ITEM_DELAY_BLOCK.setCreativeTab(TAB_REDSTONE);
        ITEM_DFC_SCREEN.setRegistryName(DFC_SCREEN.getRegistryName());
        ITEM_DFC_SCREEN.setCreativeTab(TAB_SCREEN);
        ITEM_DFC_MONITOR.setRegistryName(DFC_MONITOR.getRegistryName());
        ITEM_DFC_MONITOR.setCreativeTab(TAB_SCREEN);
        ITEM_DFC_CUSTOM.setRegistryName(DFC_CUSTOM.getRegistryName());
        ITEM_DFC_CUSTOM.setCreativeTab(TAB_SCREEN);
    }

    @SubscribeEvent
    public void onRegisterBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().register(DELAY_BLOCK);
        event.getRegistry().register(DFC_SCREEN);
        event.getRegistry().register(DFC_MONITOR);
        event.getRegistry().register(DFC_CUSTOM);
        event.getRegistry().register(DFC_SCREEN_PART);
    }

    @SubscribeEvent
    public void onRegisterItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(ITEM_DELAY_BLOCK);
        event.getRegistry().register(ITEM_DFC_SCREEN);
        event.getRegistry().register(ITEM_DFC_MONITOR);
        event.getRegistry().register(ITEM_DFC_CUSTOM);
    }
}
