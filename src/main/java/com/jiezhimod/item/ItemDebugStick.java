package com.jiezhimod.item;

import com.jiezhimod.JiezhiMod;
import com.jiezhimod.proxy.CommonProxy;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/**
 * 调试棒：**蹲下右键任意本模组的方块**打开调试界面。
 *
 * 存在的理由：有人反馈四块 DFC 屏幕同时开着只有 9 FPS。
 * 刷新率原本是写死的 0.05 秒（20Hz），每块屏每帧都要重画 768×576 的画布再整张上传纹理 ——
 * 屏幕一多就顶不住。与其让玩家去改配置，不如给一根调试棒，**按设备类型现场调**。
 *
 * 为什么要求"蹲下"：不蹲下右键会被方块自己接走（显示器要开关机、控制台要开界面、
 * 密码盘要按键）。蹲下是唯一不冲突的修饰键，也和遥控器/连接器的手感一致。
 */
public class ItemDebugStick extends Item {

    public ItemDebugStick() {
        this.setRegistryName("jiezhimod", "debug_stick");
        this.setUnlocalizedName("debug_stick");
        this.setMaxStackSize(1);
        this.setCreativeTab(ModItems.TAB);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!player.isSneaking()) {
            // 不蹲下：交给方块自己处理（开关机 / 开界面 / 按键）
            return EnumActionResult.PASS;
        }
        // 只认本模组的方块 —— 别让一根调试棒能在别人的机器上开出界面
        net.minecraft.block.Block block = world.getBlockState(pos).getBlock();
        net.minecraft.util.ResourceLocation id = block.getRegistryName();
        if (id == null || !JiezhiMod.MODID.equals(id.getResourceDomain())) {
            if (!world.isRemote) {
                player.sendStatusMessage(new TextComponentTranslation(
                        "msg.jiezhimod.debug.notmine"), true);
            }
            return EnumActionResult.SUCCESS;
        }
        if (!world.isRemote) {
            player.openGui(JiezhiMod.instance, CommonProxy.GUI_DEBUG, world,
                    pos.getX(), pos.getY(), pos.getZ());
        }
        return EnumActionResult.SUCCESS;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, World world, List<String> tip, ITooltipFlag flag) {
        tip.add(I18n.format("tooltip.jiezhimod.debug_stick.0"));
        tip.add(I18n.format("tooltip.jiezhimod.debug_stick.1"));
    }
}
