package com.jiezhimod.proxy;

import com.jiezhimod.JiezhiMod;
import com.jiezhimod.block.ModBlocks;
import com.jiezhimod.block.TileDelay;
import com.jiezhimod.client.ClientEventHandler;
import com.jiezhimod.client.ClientUiCore;
import com.jiezhimod.client.gui.GuiDelayBlock;
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
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_DELAY) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileDelay) {
                return new GuiDelayBlock((TileDelay) te);
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
        // 方块的"世界内"模型由 blockstates/delay_block.json 决定（BlockRenderType.MODEL），
        // 这里只负责物品形态（创造物品栏 / 手持 / 掉落物）。
        Item delayItem = Item.getItemFromBlock(ModBlocks.DELAY_BLOCK);
        if (delayItem != null) {
            bindItemModel(delayItem, "jiezhimod:delay_block");
        } else {
            JiezhiMod.warn("Delay block ItemBlock is missing; its item model is not bound.");
        }
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
        // DFC 自定义显示屏（第三块显示器）
        Item customItem = Item.getItemFromBlock(ModBlocks.DFC_CUSTOM);
        if (customItem != null) {
            bindItemModel(customItem, "jiezhimod:dfc_custom");
        } else {
            JiezhiMod.warn("DFC custom screen ItemBlock is missing; its item model is not bound.");
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
