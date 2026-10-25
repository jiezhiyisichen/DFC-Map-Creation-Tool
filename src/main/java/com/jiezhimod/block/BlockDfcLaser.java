package com.jiezhimod.block;

import com.jiezhimod.dfc.LaserLayout;
import com.jiezhimod.dfc.ScreenStructure;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * DFC **激光状态显示器** —— 第六块显示器（v4.2）。
 *
 * 【它和自定义屏的关系】"遥控器记录设备 → 放进 GUI 槽位 → 点添加"这一整套完全照搬，
 * 所以方块实体直接继承 {@link TileCustomScreen}（见 {@link TileLaserScreen}），
 * 右键也是打开同一个面板。差别是排版：每台设备占一个格子，格子里画一台小激光器
 * （管子 + 头部 + 电源箱），开启时从头部射出光束，下面是五行「名称 + 数值 + 数据条」。
 *
 * 【为什么值得单独一块屏】自定义屏能看"这台机器的某个罐有多少液"，但看不出
 * "这台激光开没开、够不够冷凝胶、透镜还能撑几次"。这一页把每台激光的体检结果
 * 摊开，并且快到极限时直接在底部弹一条告警。
 */
public class BlockDfcLaser extends BlockCustomScreen {

    public BlockDfcLaser() {
        // 注册名只能在构造期一次定下（二次 setRegistryName 会抛异常 = 加载即崩）
        super("dfc_laser", "dfc_laser");
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileLaserScreen();
    }

    /** 拆掉 / 放不下时返还的是"激光状态显示器"本身 */
    @Override
    protected Item displayItem() {
        return ModBlocks.ITEM_DFC_LASER;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.dfc_laser.0",
                ScreenStructure.PARTS));
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.dfc_laser.1",
                LaserLayout.SLOTS));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_laser.2"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_laser.3"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.dfc_laser.4"));
    }
}
