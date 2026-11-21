package com.jiezhimod.client.ring;

import com.jiezhimod.block.ModBlocks;
import com.jiezhimod.block.TileLightRing;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.color.IBlockColor;
import net.minecraft.client.renderer.color.IItemColor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.event.ColorHandlerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;

/**
 * 灯圈的颜色注册（v4.4）——**必须挂在 {@link ColorHandlerEvent} 上，不能在 ModelRegistryEvent 里做**。
 *
 * <h3>为什么（v4.4 上线后的崩溃复盘）</h3>
 * 第一版把 {@code Minecraft.getMinecraft().getBlockColors().registerBlockColorHandler(...)}
 * 写在了 {@code ClientProxy.onModelRegistry(ModelRegistryEvent)} 里，结果一进游戏就
 * {@code NullPointerException at ClientProxy.onModelRegistry} 崩在启动阶段。
 * 原因是原版 {@code Minecraft} 的构造顺序：
 * <pre>
 *   第 378 行  this.init();                    // 里面就会 preInit → ModelRegistryEvent
 *   第 514 行  this.blockColors = BlockColors.create();
 *   第 515 行  this.itemColors  = ItemColors.create(blockColors);
 * </pre>
 * 也就是说 **ModelRegistryEvent 触发时两个颜色注册表都还是 null**。
 * Forge 为此专门提供了 {@link ColorHandlerEvent}（在资源加载阶段、颜色表就绪之后触发），
 * 这才是 1.12.2 注册 {@code IBlockColor} / {@code IItemColor} 的唯一正确时机。
 *
 * <p>注册在 {@code MinecraftForge.EVENT_BUS}（Forge 总线）上，不是 @Mod 总线 ——
 * 所以由 {@code ClientProxy.init()} 里显式 {@code MinecraftForge.EVENT_BUS.register(new RingColorHandlers())}。
 * 资源重载时这个事件会再触发一次，{@code BlockColors} 内部是按方块覆盖的 Map，
 * 重复注册不会产生多头。</p>
 */
@SideOnly(Side.CLIENT)
public final class RingColorHandlers {

    @SubscribeEvent
    public void onBlockColors(ColorHandlerEvent.Block event) {
        event.getBlockColors().registerBlockColorHandler(new IBlockColor() {
            @Override
            public int colorMultiplier(IBlockState state, @Nullable IBlockAccess world,
                                       @Nullable BlockPos pos, int tintIndex) {
                if (tintIndex != 0) {
                    return 0xFFFFFF;
                }
                // 物品形态 / 区块还没同步时 world 或 pos 为 null：给白色（贴图本身接近白）
                if (world == null || pos == null) {
                    return 0xFFFFFF;
                }
                return TileLightRing.tint(world, pos);
            }
        }, ModBlocks.LIGHT_RING);
    }

    @SubscribeEvent
    public void onItemColors(ColorHandlerEvent.Item event) {
        event.getItemColors().registerItemColorHandler(new IItemColor() {
            @Override
            public int colorMultiplier(ItemStack stack, int tintIndex) {
                // 物品形态没有世界坐标：给白色，放下去立刻变成组颜色。
                // （想做"物品上预览颜色"的话，这里要读物品 NBT —— 但灯圈本体不存颜色，
                //   颜色属于"组"，所以物品保持中性白才是对的。）
                return 0xFFFFFF;
            }
        }, ModBlocks.LIGHT_RING);
    }
}
