package com.jiezhimod;

import com.jiezhimod.block.ModBlocks;
import com.jiezhimod.countdown.ServerCountdownManager;
import com.jiezhimod.seal.SealA;
import com.jiezhimod.seal.SealB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.Logger;

@Mod(modid = JiezhiMod.MODID, name = JiezhiMod.NAME, version = JiezhiMod.VERSION)
public class JiezhiMod
{
    public static final String MODID = "jiezhimod";
    public static final String NAME = "JiezhiMod";
    public static final String VERSION = "4.0";

    @Instance(MODID)
    public static JiezhiMod instance;

    public JiezhiMod() {
        // 兜底：保证 preInit 前 instance 一定可用（FML 也会用 @Instance 注入同一个对象）
        instance = this;
    }

    /** 3D banner (banner3-D) style ASCII art logo, printed to the log on load. */
    private static final String LOGO =
            "::::::'##:'####:'########:'########:'##::::'##:'####:\n" +
            ":::::: ##:. ##:: ##.....::..... ##:: ##:::: ##:. ##::\n" +
            ":::::: ##:: ##:: ##::::::::::: ##::: ##:::: ##:: ##::\n" +
            ":::::: ##:: ##:: ######:::::: ##:::: #########:: ##::\n" +
            "'##::: ##:: ##:: ##...:::::: ##::::: ##.... ##:: ##::\n" +
            " ##::: ##:: ##:: ##:::::::: ##:::::: ##:::: ##:: ##::\n" +
            ". ######::'####: ########: ########: ##:::: ##:'####:\n" +
            ":......:::....::........::........::..:::::..::....::";

    private static Logger logger;

    /**
     * 供代理类安全地打日志：logger 在 preInit 之后才有值，这里做一次空安全。
     * 早期（模型注册等阶段）调用不会 NPE，只会安静跳过。
     */
    public static void warn(String message, Object... args) {
        if (logger != null) {
            logger.warn(message, args);
        }
    }

    @SidedProxy(
            clientSide = "com.jiezhimod.proxy.ClientProxy",
            serverSide = "com.jiezhimod.proxy.CommonProxy"
    )
    public static com.jiezhimod.proxy.CommonProxy proxy;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        SealA.verify();
        SealB.verify();
        logger = event.getModLog();
        logger.info("\n" + LOGO);
        logger.info("{} v{} is loading.", NAME, VERSION);
        com.jiezhimod.network.ModNetwork.init();
        // 方块 + 专属方块页 jiezhimod-红石
        MinecraftForge.EVENT_BUS.register(new ModBlocks());
        // DFC 显示器的方块实体。**必须注册**：TileEntity.writeToNBT 里会做
        // REGISTRY.getNameForObject(this.getClass())，查不到就抛
        // "xxx is missing a mapping! This is a bug!"（区块保存时炸，并且
        // getUpdateTag 也走同一条路 —— 客户端永远收不到开机状态，屏幕一黑到底）。
        GameRegistry.registerTileEntity(com.jiezhimod.block.TileDfcScreen.class,
                new ResourceLocation(MODID, "dfc_screen"));
        // 自定义显示屏的方块实体。**子类也必须单独注册**：
        // TileEntity.getKey(Class) 是按**具体类**查表的，不会顺着继承链找父类的映射，
        // 父类注册过也不顶用。查不到的结果是 writeToNBT 里那句
        // "xxx is missing a mapping! This is a bug!" 直接抛穿 —— 区块保存时炸；
        // 就算侥幸不炸，"id" 标签也不会写进 NBT，区块重新加载时这个方块实体会被丢掉，
        // 玩家辛苦接好的 8 条数据全没了。
        GameRegistry.registerTileEntity(com.jiezhimod.block.TileCustomScreen.class,
                new ResourceLocation(MODID, "dfc_custom"));
        // 密码盘 / 密码控制台的方块实体。同样是**按具体类**查表，
        // 漏注册的症状：区块保存时抛 "missing a mapping!"，
        // 而随机密码存在方块实体里 —— 那台密码盘的密码会整个丢掉，便签上显示 ------。
        GameRegistry.registerTileEntity(com.jiezhimod.block.TileKeypad.class,
                new ResourceLocation(MODID, "keypad"));
        GameRegistry.registerTileEntity(com.jiezhimod.block.TilePasswordConsole.class,
                new ResourceLocation(MODID, "password_console"));
        // 高级红石中继器的方块实体：延迟存在这里（方块状态装不下 1200 档）。
        // 老规矩**按具体类**注册，漏了就"missing a mapping!"，存档一读就炸。
        GameRegistry.registerTileEntity(com.jiezhimod.block.TileAdvancedRepeater.class,
                new ResourceLocation(MODID, "advanced_repeater"));
        NetworkRegistry.INSTANCE.registerGuiHandler(instance, proxy);
        // 服务端倒计时管理（ServerTick/PlayerLoggedIn 仅在服务端与集成服务器触发）
        MinecraftForge.EVENT_BUS.register(new ServerCountdownManager());
        // 物品（加速/重力/治愈线圈）注册 + 专属物品页
        MinecraftForge.EVENT_BUS.register(new com.jiezhimod.item.ModItems());
        // 喷气背包：胸甲槽装备 + 手持开关按住右键起飞（服务端权威 + 热量系统）
        MinecraftForge.EVENT_BUS.register(new com.jiezhimod.item.JetpackEffect());
        // 玩家登出时清掉热量状态，避免 map 无限增长
        MinecraftForge.EVENT_BUS.register(new com.jiezhimod.item.JetpackLogoutHandler());
        // 线圈生效逻辑：加速（MOVEMENT_SPEED 倍率修饰符）
        MinecraftForge.EVENT_BUS.register(new com.jiezhimod.item.SpeedCoilEffect());
        // 线圈生效逻辑：重力（LivingJumpEvent 里改跳跃初速度，约 6 格）
        MinecraftForge.EVENT_BUS.register(new com.jiezhimod.item.GravityCoilEffect());
        // 线圈生效逻辑：治愈（每 20 tick heal 4 点，仅服务端）
        MinecraftForge.EVENT_BUS.register(new com.jiezhimod.item.HealingCoilEffect());
        proxy.preInit();
    }

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        proxy.init();
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event)
    {
        SealA.verify();
        SealB.verify();
        // 新开一局：密码盘的随机密码靠这个令牌判断"该换一批了"（见 KeypadSession）
        com.jiezhimod.keypad.KeypadSession.newSession();
        event.registerServerCommand(new com.jiezhimod.command.CommandCameraShake());
        event.registerServerCommand(new com.jiezhimod.command.CommandWhiteScreen());
        event.registerServerCommand(new com.jiezhimod.command.CommandSubtitle());
        event.registerServerCommand(new com.jiezhimod.command.CommandSubtitleClear());
        event.registerServerCommand(new com.jiezhimod.command.CommandCountdownAdd());
        event.registerServerCommand(new com.jiezhimod.command.CommandCountdownRemove());
        event.registerServerCommand(new com.jiezhimod.command.CommandCountdownStop());
        event.registerServerCommand(new com.jiezhimod.command.CommandCountdownContinue());
        event.registerServerCommand(new com.jiezhimod.command.CommandCountdownTest());
        event.registerServerCommand(new com.jiezhimod.command.CommandNotice());
    }

    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event)
    {
        ServerCountdownManager.clear();
    }
}
