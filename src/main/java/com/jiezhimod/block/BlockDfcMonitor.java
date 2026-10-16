package com.jiezhimod.block;

import com.jiezhimod.dfc.ScreenStructure;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * DFC **部件监视器** —— 第二块显示器，和 {@link BlockDfcScreen} 是同一台硬件。
 *
 * 【为什么直接继承而不是复制一份】
 * 两者的几何（4x3 吊装多方块）、外壳模型、方块实体、TESR、开机动画、网络同步
 * 全部一模一样，**唯一的差别只是主界面画哪一页数据**：
 *   核心显示器 -> 温度/稳定度/能量/崩溃圆盘/日志（并带崩溃告警）
 *   部件监视器 -> 发射器/接收器/燃料/燃料喷射器/积蓄能量/能量爆发
 *
 * 页面由 {@code RenderDfcScreen} 按"控制器方块是不是本类"来决定，所以这里
 * 只需要改注册名、掉落物与提示文字；连方块实体都不用换。
 *
 * 【占位块复用】11 个占位块继续用 {@link BlockDfcScreenPart} ——
 * 它只负责"提供碰撞 + 记录朝向 + 被拆时带走整块结构"，
 * 而 {@code ScreenMultiblock.findController} 找的是 {@code BlockDfcScreen}，
 * 本类是它的子类，天然落在同一套查找逻辑里。
 */
public class BlockDfcMonitor extends BlockDfcScreen {

    public BlockDfcMonitor() {
        // 注册名交给父类构造期一次性设定：IForgeRegistryEntry 不允许二次 setRegistryName，
        // 在外面补设会直接抛 IllegalStateException，静态初始化即崩。
        super("dfc_monitor", "dfc_monitor");
    }

    /** 拆掉 / 放不下时返还的是"部件监视器"本身，不能变成一块核心屏 */
    @Override
    protected Item displayItem() {
        return ModBlocks.ITEM_DFC_MONITOR;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.dfc_monitor.0",
                ScreenStructure.PARTS));
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.dfc_monitor.1"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_monitor.2"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_monitor.3"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_monitor.4"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_monitor.5"));
    }
}
