package com.jiezhimod.proxy;

import com.jiezhimod.JiezhiMod;
import com.jiezhimod.block.ModBlocks;
import com.jiezhimod.client.ClientEventHandler;
import com.jiezhimod.client.ClientUiCore;
import com.jiezhimod.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class ClientProxy extends CommonProxy {
    @Override
    public void preInit() {
        MinecraftForge.EVENT_BUS.register(new ClientEventHandler());
        MinecraftForge.EVENT_BUS.register(this);
        // ★★ 灯圈的颜色（IBlockColor / IItemColor）**必须在这里注册，不能放 init()**。
        //
        // 原版 Minecraft 构造函数的真实顺序（对着 forge-...-patched.jar 的 Minecraft.java 数的）：
        //   第 467 行  beginMinecraftLoading() → loadMods() → preinitializeMods()  ⇒ 模组 preInit
        //   第 514 行  this.blockColors = BlockColors.create()
        //                └─ 它内部就 post ColorHandlerEvent.Block（BlockColors.java:163）
        //   第 515 行  this.itemColors  = ItemColors.create(blockColors)  ⇒ 同理 post Item 事件
        //   第 535 行  finishMinecraftLoading() → initializeMods()                ⇒ 模组 init
        //
        // 也就是说 **颜色事件在 init 之前就已经发完了**。原来写在 init() 里注册监听器，
        // 事件早跑过去了 —— 于是 IBlockColor 从没注册上，灯圈永远显示贴图原色（白），
        // 服务端把颜色算得再对（日志里 sync -> #FF3A3A）画面也不会变。
        // 这是 v4.4 里"灯圈不变色 / 渐变无效"的真正根因。
        MinecraftForge.EVENT_BUS.register(new com.jiezhimod.client.ring.RingColorHandlers());
        // 灯圈周围的**彩色光晕**：原版方块光只有亮度没有颜色（光照贴图是一张 16x16 灰度表
        // 乘一个全局 RGB），"让地面被染成灯的颜色"没有接口可调，只能在方块外面自己画一层
        // 加法混合的彩色壳。放 preInit 是为了和颜色处理器同批注册，便于一眼看全。
        MinecraftForge.EVENT_BUS.register(new com.jiezhimod.client.ring.RingGlowRenderer());
    }

    @Override
    public void init() {
        // 自定义聊天栏 / 右下角通知叠加 / 通知历史快捷键
        MinecraftForge.EVENT_BUS.register(new ClientUiCore());
        // 入游提示（v5.2.1）：进世界后在原版聊天栏打一条 "jiezhimod 版本：x.y.z，已经加载…"。
        // 只在客户端注册；内部用 armed 标志保证每个世界只发一次，详见 WelcomeNotice 的类注释。
        MinecraftForge.EVENT_BUS.register(new com.jiezhimod.client.WelcomeNotice());
        // 显示器画布的"每帧报到"：重绘预算靠它分帧，漏了就会变成几秒才动一下
        MinecraftForge.EVENT_BUS.register(new com.jiezhimod.client.dfc.ScreenFrameHook());
        ClientRegistry.registerKeyBinding(ClientUiCore.KEY_NOTICE);
        // 屏幕空间聚光灯（SSS）pass。
        // ★ v5.1.1：开关默认改为**开启** —— 从 5.0 起这就是默认视觉特性（MODE=2 画的是光照，
        //   不再是早期那张黑白深度灰度图），不该再要求玩家每次手动加 JVM 参数。
        //   一条日志都不打就"没光"，排查成本太高（5.1.0 就栽在这里：参数丢失 → 整个 pass 不注册，
        //   日志里连个 [SSS] 前缀都没有）。
        com.jiezhimod.client.render.DepthDebugPass.registerIfEnabled();
        // 喷气尾焰粒子的工厂注册由 ClientEventHandler 在客户端 tick 里懒注册
        // （effectRenderer 在 init 阶段还没建好，那时注册会拿到 null）。
        // DFC 显示器的渲染器：整块设备（外壳 + 支架 + 屏幕）全部由 TESR 画，
        // 方块自身 getRenderType = INVISIBLE，所以这里必须绑定，否则什么都看不见。
        ClientRegistry.bindTileEntitySpecialRenderer(
                com.jiezhimod.block.TileDfcScreen.class,
                new com.jiezhimod.client.dfc.RenderDfcScreen());
        // 密码盘：方块本身 INVISIBLE（模型画不了动态纹理），底座 + 面板全靠 TESR
        ClientRegistry.bindTileEntitySpecialRenderer(
                com.jiezhimod.block.TileKeypad.class,
                new com.jiezhimod.client.keypad.RenderKeypad());
        // 旋转警示灯（v4.5）：底座 / 圆灯罩 / 旋转灯芯 / 光锥全由 TESR 现画 ——
        // 贴图画不出「完美的圆」，而且光锥是几何体，烘焙模型根本表达不了。
        ClientRegistry.bindTileEntitySpecialRenderer(
                com.jiezhimod.block.TileAlarmLight.class,
                new com.jiezhimod.client.render.RenderAlarmLight());
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        // 排错用：服务端发出 openGui 之后，客户端到底有没有解析出界面
        JiezhiMod.warn("[GuiClient] id=" + id + " pos=" + x + "," + y + "," + z
                + " te=" + (world.getTileEntity(new BlockPos(x, y, z)) == null ? "null"
                : world.getTileEntity(new BlockPos(x, y, z)).getClass().getSimpleName()));
        if (id == GUI_ALARM_CONSOLE) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof com.jiezhimod.block.TileAlarmConsole) {
                return new com.jiezhimod.client.gui.GuiAlarmConsole(
                        (com.jiezhimod.block.TileAlarmConsole) te);
            }
            return null;
        }
        if (id == GUI_DEBUG) {
            BlockPos p = new BlockPos(x, y, z);
            return new com.jiezhimod.client.gui.GuiDebug(p,
                    new com.jiezhimod.block.ContainerDebug(p));
        }
        if (id == GUI_PASSWORD_CONSOLE) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof com.jiezhimod.block.TilePasswordConsole) {
                com.jiezhimod.block.TilePasswordConsole tile =
                        (com.jiezhimod.block.TilePasswordConsole) te;
                // 客户端自己造一份同参数的容器交给 GuiContainer（没有物品槽，只做距离校验）
                return new com.jiezhimod.client.keypad.GuiPasswordConsole(tile,
                        new com.jiezhimod.block.ContainerPasswordConsole(tile));
            }
        }
        if (id == GUI_ADVANCED_REPEATER) {
            BlockPos p = new BlockPos(x, y, z);
            // 容器用坐标构造（不持有方块实体）：客户端这份 TE 只是镜像
            return new com.jiezhimod.client.gui.GuiAdvancedRepeater(p,
                    new com.jiezhimod.block.ContainerAdvancedRepeater(p));
        }
        if (id == GUI_RING_CONSOLE) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof com.jiezhimod.block.TileRingConsole) {
                com.jiezhimod.block.TileRingConsole tile =
                        (com.jiezhimod.block.TileRingConsole) te;
                return new com.jiezhimod.client.gui.GuiRingConsole(tile,
                        new com.jiezhimod.block.ContainerRingConsole(tile));
            }
        }
        if (id == GUI_LASER_SCREEN) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            // ★ 旧存档兜底：这一格的方块是激光屏、方块实体却还是自定义屏的
            //   （4.4 之前的 createTileEntity 坑）。客户端也得就地换掉，
            //   否则这里 instanceof 不成立 → 服务端开了面板、客户端拿不到 GUI。
            if (te instanceof com.jiezhimod.block.TileDfcScreen
                    && !(te instanceof com.jiezhimod.block.TileLaserScreen)) {
                if (((com.jiezhimod.block.TileDfcScreen) te).healScreenTile()) {
                    te = world.getTileEntity(new BlockPos(x, y, z));
                }
            }
            if (te instanceof com.jiezhimod.block.TileLaserScreen) {
                com.jiezhimod.block.TileLaserScreen tile =
                        (com.jiezhimod.block.TileLaserScreen) te;
                // 客户端自己造一份同参数的容器：槽位坐标/顺序必须和服务端那份逐一对齐
                com.jiezhimod.block.ContainerLaserScreen container =
                        new com.jiezhimod.block.ContainerLaserScreen(player.inventory, tile);
                return new com.jiezhimod.client.gui.GuiLaserScreen(tile, container);
            }
        }
        if (id == GUI_CUSTOM_SCREEN) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            // ★ 服务端那份容器已经改道了（见 CommonProxy），客户端必须跟着改道，
            //   否则两边容器不一致 = 槽位错位 / 物品凭空消失。判据一模一样。
            if (te instanceof com.jiezhimod.block.TileLaserScreen) {
                com.jiezhimod.block.TileLaserScreen laser =
                        (com.jiezhimod.block.TileLaserScreen) te;
                return new com.jiezhimod.client.gui.GuiLaserScreen(laser,
                        new com.jiezhimod.block.ContainerLaserScreen(player.inventory, laser));
            }
            if (te instanceof com.jiezhimod.block.TileCustomScreen) {
                com.jiezhimod.block.TileCustomScreen tile =
                        (com.jiezhimod.block.TileCustomScreen) te;
                // 客户端**自己造一份同参数的容器**交给 GuiContainer：
                // 槽位坐标、槽位顺序必须和服务端那份逐一对齐，否则点到的格子会错位。
                // 槽里的物品由服务端通过 S2FPacketSetSlot 同步过来。
                com.jiezhimod.block.ContainerCustomScreen container =
                        new com.jiezhimod.block.ContainerCustomScreen(player.inventory, tile);
                return new com.jiezhimod.client.gui.GuiCustomScreen(tile, container);
            }
        }
        return null;
    }

    /**
     * 物品模型注册（仅客户端事件），把物品指向 assets/jiezhimod/models/item/&lt;name&gt;.json
     *
     * 每一项都单独 try/catch：ModelRegistryEvent 里抛一次异常会让**后面所有**模型
     * 都不注册，线圈和方块会一起变成"缺模型"的紫黑格。这里绝不能连坐。
     */
    @SubscribeEvent
    public void onModelRegistry(ModelRegistryEvent event) {
        bindItemModel(ModItems.SPEED_COIL, "jiezhimod:speed_coil");
        bindItemModel(ModItems.GRAVITY_COIL, "jiezhimod:gravity_coil");
        bindItemModel(ModItems.HEALING_COIL, "jiezhimod:healing_coil");
        // 喷气背包 / 喷气开关
        bindItemModel(ModItems.JETPACK, "jiezhimod:jetpack");
        bindItemModel(ModItems.JET_SWITCH, "jiezhimod:jet_switch");
        // 方块的"世界内"模型由各自的 blockstates 决定，这里只负责物品形态
        // DFC 显示器 / 遥控器
        Item screenItem = Item.getItemFromBlock(ModBlocks.DFC_SCREEN);
        if (screenItem != null) {
            bindItemModel(screenItem, "jiezhimod:dfc_screen");
        } else {
            JiezhiMod.warn("DFC screen ItemBlock is missing; its item model is not bound.");
        }
        // DFC 部件监视器（第二块显示器）
        Item monitorItem = Item.getItemFromBlock(ModBlocks.DFC_MONITOR);
        if (monitorItem != null) {
            bindItemModel(monitorItem, "jiezhimod:dfc_monitor");
        } else {
            JiezhiMod.warn("DFC monitor ItemBlock is missing; its item model is not bound.");
        }
        bindItemModel(ModItems.DFC_REMOTE, "jiezhimod:dfc_remote");
        // 密码盘 / 密码控制台 / 密码连接器
        Item keypadItem = Item.getItemFromBlock(ModBlocks.KEYPAD);
        if (keypadItem != null) {
            bindItemModel(keypadItem, "jiezhimod:keypad");
        } else {
            JiezhiMod.warn("Keypad ItemBlock is missing; its item model is not bound.");
        }
        Item consoleItem = Item.getItemFromBlock(ModBlocks.PASSWORD_CONSOLE);
        if (consoleItem != null) {
            bindItemModel(consoleItem, "jiezhimod:password_console");
        } else {
            JiezhiMod.warn("Password console ItemBlock is missing; its item model is not bound.");
        }
        // 高级红石中继器：**方块模型走 blockstates、物品模型走这里**，
        // 漏了这一行创造页里就是紫黑格（v3.9.6 的曲线屏踩过一次）
        Item repeaterItem = Item.getItemFromBlock(ModBlocks.ADVANCED_REPEATER);
        if (repeaterItem != null) {
            bindItemModel(repeaterItem, "jiezhimod:advanced_repeater");
        } else {
            JiezhiMod.warn("Advanced repeater ItemBlock is missing; its item model is not bound.");
        }
        bindItemModel(ModItems.CONNECTOR, "jiezhimod:connector");
        // 激光链接器（激光状态显示器专用，和遥控器分开）
        bindItemModel(ModItems.LASER_CONNECTOR, "jiezhimod:laser_connector");
        // 灯圈 / 灯圈控制台 / 灯圈链接器（v4.4）
        //
        // 【颜色不在这里注册】灯圈的 tint（IBlockColor）必须挂在 ColorHandlerEvent 上：
        // 原版 Minecraft 的构造顺序是 init()（会触发 ModelRegistryEvent）在前、
        // blockColors / itemColors 赋值在后 —— 在这里拿 Minecraft.getBlockColors() 是 null，
        // v4.4 第一版正是因此崩在启动阶段（NPE at ClientProxy.onModelRegistry）。
        // 见 com.jiezhimod.client.ring.RingColorHandlers。
        Item ringItem = Item.getItemFromBlock(ModBlocks.LIGHT_RING);
        if (ringItem != null) {
            bindItemModel(ringItem, "jiezhimod:light_ring");
        } else {
            JiezhiMod.warn("Light ring ItemBlock is missing; its item model is not bound.");
        }

        // 旋转警示灯 / 控制台 / 链接器（v4.5）
        // ★ 每个 ItemBlock 都必须在这里绑一次模型，漏了物品栏里就是紫黑格
        //   （自测有一条 "这些 ItemBlock 没在 onModelRegistry 里绑定模型" 专门盯这个）。
        Item alarmLightItem = Item.getItemFromBlock(ModBlocks.ALARM_LIGHT);
        if (alarmLightItem != null) {
            bindItemModel(alarmLightItem, "jiezhimod:alarm_light");
        }
        Item alarmConsoleItem = Item.getItemFromBlock(ModBlocks.ALARM_CONSOLE);
        if (alarmConsoleItem != null) {
            bindItemModel(alarmConsoleItem, "jiezhimod:alarm_console");
        }
        bindItemModel(com.jiezhimod.item.ModItems.ALARM_CONNECTOR, "jiezhimod:alarm_connector");
        Item ringConsoleItem = Item.getItemFromBlock(ModBlocks.RING_CONSOLE);
        if (ringConsoleItem != null) {
            bindItemModel(ringConsoleItem, "jiezhimod:ring_console");
        } else {
            JiezhiMod.warn("Ring console ItemBlock is missing; its item model is not bound.");
        }
        bindItemModel(ModItems.RING_CONNECTOR, "jiezhimod:ring_connector");

        bindItemModel(ModItems.DEBUG_STICK, "jiezhimod:debug_stick");
        // DFC 自定义显示屏（第三块显示器）
        Item customItem = Item.getItemFromBlock(ModBlocks.DFC_CUSTOM);
        if (customItem != null) {
            bindItemModel(customItem, "jiezhimod:dfc_custom");
        } else {
            JiezhiMod.warn("DFC custom screen ItemBlock is missing; its item model is not bound.");
        }
        // DFC 曲线显示器（第四块显示器）
        // 【漏了这一行的后果】物品栏里显示成紫黑格（缺模型），而 tooltip、名字、
        // 放下去之后的方块全都正常 —— 因为方块模型走 blockstates，
        // 物品模型走这里。v3.9.6 就是漏了这一行（实机截图：创造页第一格是紫黑方块）。
        Item graphItem = Item.getItemFromBlock(ModBlocks.DFC_GRAPH);
        if (graphItem != null) {
            bindItemModel(graphItem, "jiezhimod:dfc_graph");
        } else {
            JiezhiMod.warn("DFC graph screen ItemBlock is missing; its item model is not bound.");
        }
        // DFC 标注显示器（第五块显示器）：同样别漏，漏了创造页里就是紫黑格
        Item labelItem = Item.getItemFromBlock(ModBlocks.DFC_LABEL);
        if (labelItem != null) {
            bindItemModel(labelItem, "jiezhimod:dfc_label");
        } else {
            JiezhiMod.warn("DFC label screen ItemBlock is missing; its item model is not bound.");
        }
        // DFC 激光状态显示器（第六块显示器）
        Item laserItem = Item.getItemFromBlock(ModBlocks.DFC_LASER);
        if (laserItem != null) {
            bindItemModel(laserItem, "jiezhimod:dfc_laser");
        } else {
            JiezhiMod.warn("DFC laser screen ItemBlock is missing; its item model is not bound.");
        }
    }

    private void bindItemModel(Item item, String modelPath) {
        try {
            if (item == null) {
                return;
            }
            ModelLoader.setCustomModelResourceLocation(item, 0,
                    new ModelResourceLocation(modelPath, "inventory"));
        } catch (RuntimeException e) {
            // 吞掉：单个模型注册失败不该让整个模组的模型全丢
            JiezhiMod.warn("Failed to bind item model {} for {}", modelPath, String.valueOf(item));
        }
    }
}
