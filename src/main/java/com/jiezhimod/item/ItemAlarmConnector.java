package com.jiezhimod.item;

import com.jiezhimod.block.BlockAlarmConsole;
import com.jiezhimod.block.BlockAlarmLight;
import com.jiezhimod.block.TileAlarmConsole;
import com.jiezhimod.block.TileAlarmLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 警示灯链接器（v4.5）：两种接法，靠"右键的是什么方块"区分，不需要切模式。
 *
 * <h3>A. 接控制台（手动控制颜色 / 转速）</h3>
 * <ol>
 *   <li><b>蹲下右键</b>一盏旋转警示灯 → 记下它；</li>
 *   <li><b>蹲下右键</b>警示灯控制台 → 把它加进那台控制台的成员表。</li>
 * </ol>
 *
 * <h3>B. 接显示屏（告警联动）</h3>
 * <ol>
 *   <li><b>蹲下右键</b>任意一块 DFC 显示屏（核心屏 / 部件屏 / 激光屏 / 自定义屏 / 标注屏）
 *       → 记下它当**数据源**；</li>
 *   <li><b>蹲下右键</b>一盏警示灯 → 这盏灯从此跟着那块屏的告警等级走。</li>
 * </ol>
 * 屏幕自己不会"推"数据 —— {@code TileAlarmLight} 每 10 刻读一次
 * {@code TileDfcScreen.getData()}，等级变了才改颜色。这样灯可以离屏很远，
 * 也不用给每块屏都加一套发射逻辑。
 *
 * <h3>C. 清空</h3>
 * <b>蹲下右键空气</b> → 记录（灯和数据源）一起清掉。
 *
 * <p>【不蹲下必须 PASS】{@code onItemUse} 返回 SUCCESS 会让
 * {@code Block.onBlockActivated} 整个不执行 —— 拿着链接器就再也打不开控制台界面了。
 * 这是另外三支链接器都踩过的坑（docs/踩坑记录 #16）。</p>
 */
public class ItemAlarmConnector extends Item {

    private static final String K_X = "AlarmX";
    private static final String K_Y = "AlarmY";
    private static final String K_Z = "AlarmZ";
    private static final String K_HAS = "AlarmHas";
    /** 数据源（显示屏）坐标：与上面那组**分开存**，两种接法可以先后操作，不互相覆盖 */
    private static final String K_SX = "AlarmSrcX";
    private static final String K_SY = "AlarmSrcY";
    private static final String K_SZ = "AlarmSrcZ";
    private static final String K_SHAS = "AlarmSrcHas";

    public ItemAlarmConnector() {
        this.setRegistryName("jiezhimod", "alarm_connector");
        this.setUnlocalizedName("alarm_connector");
        this.setMaxStackSize(1);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!player.isSneaking()) {
            return EnumActionResult.PASS;
        }
        if (world.isRemote) {
            return EnumActionResult.SUCCESS;
        }
        ItemStack stack = player.getHeldItem(hand);
        IBlockState state = world.getBlockState(pos);

        // A. 蹲下右键控制台 = 把记录的那盏灯加进成员表
        if (state.getBlock() instanceof BlockAlarmConsole) {
            TileEntity te = world.getTileEntity(pos);
            if (!(te instanceof TileAlarmConsole)) {
                return EnumActionResult.SUCCESS;
            }
            BlockPos lamp = readRecord(stack);
            if (lamp == null) {
                player.sendStatusMessage(new TextComponentString(
                        TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                                + TextFormatting.YELLOW + "链接器里还没有记录警示灯。先蹲下右键一盏警示灯。"), false);
                return EnumActionResult.SUCCESS;
            }
            TileAlarmConsole console = (TileAlarmConsole) te;
            if (!console.addMember(lamp)) {
                player.sendStatusMessage(new TextComponentString(
                        TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                                + TextFormatting.RED + "这台控制台已经绑满 " + TileAlarmConsole.MAX_MEMBERS
                                + " 盏灯了。"), false);
                return EnumActionResult.SUCCESS;
            }
            // 显式绑控制台 = 交回手动控制：先解除告警联动，否则控制台的推送会被忽略
            TileEntity lt = world.getTileEntity(lamp);
            if (lt instanceof TileAlarmLight) {
                ((TileAlarmLight) lt).clearSource();
            }
            console.pushConfig();
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.GREEN + "已绑定警示灯 " + lamp.getX() + " " + lamp.getY()
                            + " " + lamp.getZ() + TextFormatting.GRAY
                            + "  (本控制台共 " + console.members().size() + " 盏)"), false);
            return EnumActionResult.SUCCESS;
        }

        // B. 蹲下右键**显示屏** = 记录告警数据源
        TileEntity anyTe = world.getTileEntity(pos);
        if (anyTe instanceof com.jiezhimod.block.TileDfcScreen) {
            writeSource(stack, pos);
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.GREEN + "已记录告警数据源 " + pos.getX() + " "
                            + pos.getY() + " " + pos.getZ() + TextFormatting.GRAY
                            + "  (蹲下右键警示灯完成联动)"), false);
            return EnumActionResult.SUCCESS;
        }

        // C. 蹲下右键警示灯
        if (state.getBlock() instanceof BlockAlarmLight) {
            TileEntity te = anyTe;
            if (!(te instanceof TileAlarmLight)) {
                return EnumActionResult.SUCCESS;
            }
            TileAlarmLight light = (TileAlarmLight) te;
            BlockPos src = readSource(stack);
            if (src != null) {
                // C1. 手上记着数据源 → 把这盏灯接上去
                light.bindSource(src);
                int lv = Math.max(0, light.alertLevel());
                player.sendStatusMessage(new TextComponentString(
                        TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                                + TextFormatting.GREEN + "警示灯已联动到显示屏 "
                                + src.getX() + " " + src.getY() + " " + src.getZ()
                                + TextFormatting.GRAY + "  当前状态："
                                + TextFormatting.RESET + levelName(lv)), false);
                player.sendStatusMessage(new TextComponentString(
                        TextFormatting.DARK_GRAY + "提示：联动期间控制台的颜色推送对这盏灯无效；"
                                + "重新用链接器绑控制台即可改回手动。"), false);
                return EnumActionResult.SUCCESS;
            }
            // C2. 手上没有数据源 → 按老流程记录这盏灯，等接控制台
            writeRecord(stack, pos);
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.GREEN + "已记录警示灯 " + pos.getX() + " " + pos.getY()
                            + " " + pos.getZ() + TextFormatting.GRAY + "  (蹲下右键控制台完成绑定)"), false);
            return EnumActionResult.SUCCESS;
        }

        // D. 点到别的方块：明说，别让玩家以为没反应
        player.sendStatusMessage(new TextComponentString(
                TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                        + TextFormatting.YELLOW + "这不是警示灯 / 控制台 / 显示屏"), true);
        return EnumActionResult.SUCCESS;
    }

    /** 等级 → 可翻译文案（聊天栏里给玩家看"现在到底什么状态"） */
    private static net.minecraft.util.text.ITextComponent levelName(int level) {
        return new net.minecraft.util.text.TextComponentTranslation(
                "alert.jiezhimod.level." + Math.max(0, Math.min(3, level)));
    }

    /** 蹲下右键空气 = 清空记录 */
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!player.isSneaking()) {
            return new ActionResult<ItemStack>(EnumActionResult.PASS, stack);
        }
        if (!world.isRemote) {
            clearRecord(stack);
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.YELLOW + "警示灯链接器已清空。"), true);
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
    }

    // ==================== NBT ====================

    private static void writeRecord(ItemStack stack, BlockPos pos) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setBoolean(K_HAS, true);
        tag.setInteger(K_X, pos.getX());
        tag.setInteger(K_Y, pos.getY());
        tag.setInteger(K_Z, pos.getZ());
    }

    @Nullable
    public static BlockPos readRecord(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound() || !stack.getTagCompound().getBoolean(K_HAS)) {
            return null;
        }
        NBTTagCompound t = stack.getTagCompound();
        return new BlockPos(t.getInteger(K_X), t.getInteger(K_Y), t.getInteger(K_Z));
    }

    /** 记下告警数据源（一块显示屏） */
    private static void writeSource(ItemStack stack, BlockPos pos) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setBoolean(K_SHAS, true);
        tag.setInteger(K_SX, pos.getX());
        tag.setInteger(K_SY, pos.getY());
        tag.setInteger(K_SZ, pos.getZ());
    }

    @Nullable
    public static BlockPos readSource(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound() || !stack.getTagCompound().getBoolean(K_SHAS)) {
            return null;
        }
        NBTTagCompound t = stack.getTagCompound();
        return new BlockPos(t.getInteger(K_SX), t.getInteger(K_SY), t.getInteger(K_SZ));
    }

    public static void clearRecord(ItemStack stack) {
        NBTTagCompound tag = stack == null ? null : stack.getTagCompound();
        if (tag == null) {
            return;
        }
        tag.removeTag(K_HAS);
        tag.removeTag(K_X);
        tag.removeTag(K_Y);
        tag.removeTag(K_Z);
        tag.removeTag(K_SHAS);
        tag.removeTag(K_SX);
        tag.removeTag(K_SY);
        tag.removeTag(K_SZ);
    }

    // ==================== 提示 ====================

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
                               ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.alarm_connector.0"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.alarm_connector.1"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.alarm_connector.2"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.alarm_connector.3"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.alarm_connector.4"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.alarm_connector.5"));
    }
}
