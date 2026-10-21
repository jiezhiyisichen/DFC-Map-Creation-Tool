package com.jiezhimod.proxy;

import com.jiezhimod.JiezhiMod;
import com.jiezhimod.block.ModBlocks;
import com.jiezhimod.client.ClientEventHandler;
import com.jiezhimod.client.ClientUiCore;
import com.jiezhimod.item.ModItems;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
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
    }

    @Override
    public void init() {
        // 自定义聊天栏 / 右下角通知叠加 / 通知历史快捷键
        MinecraftForge.EVENT_BUS.register(new ClientUiCore());
        ClientRegistry.registerKeyBinding(ClientUiCore.KEY_NOTICE);
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
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        // 排错用：服务端发出 openGui 之后，客户端到底有没有解析出界面
        JiezhiMod.warn("[GuiClient] id=" + id + " pos=" + x + "," + y + "," + z
                + " te=" + (world.getTileEntity(new BlockPos(x, y, z)) == null ? "null"
                : world.getTileEntity(new BlockPos(x, y, z)).getClass().getSimpleName()));
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
        if (id == GUI_CUSTOM_SCREEN) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
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
        bindItemModel(ModItems.CONNECTOR, "jiezhimod:connector");
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
