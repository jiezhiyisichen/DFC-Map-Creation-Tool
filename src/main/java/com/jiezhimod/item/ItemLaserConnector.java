package com.jiezhimod.item;

import com.jiezhimod.block.ScreenMultiblock;
import com.jiezhimod.block.TileLaserScreen;
import com.jiezhimod.dfc.DfcBridge;
import com.jiezhimod.dfc.LaserInfo;
import com.jiezhimod.dfc.LaserLayout;
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
 * 激光链接器：专门给 {@link com.jiezhimod.block.BlockDfcLaser 激光状态显示器} 配设备的小工具。
 *
 * <h3>为什么要一个单独的物品，而不是继续用 DFC 遥控器</h3>
 * 遥控器同时管着"核心屏绑定反应堆源"和"自定义屏接外部设备"两件事，
 * 激光屏再挂上去就变成三套语义挤在一个物品上：同一个物品在不同屏上做的事不一样，
 * 玩家一定会记错（实机反馈就是"设置 SL 却把核心记进去了"）。
 * 这里把激光这条线整个搬出来，遥控器只管它原来的两块屏，**两边互不干扰**。
 *
 * <h3>怎么用</h3>
 * <ol>
 *   <li><b>蹲下右键</b>一台激光部件（稳定器 / 发射器 / 接收器 / 燃料喷射器）→ 记下它；</li>
 *   <li><b>右键</b>激光状态显示器 → 打开面板，把链接器放进左边槽位，点「添加」；</li>
 *   <li><b>蹲下右键空气</b> → 清空全部记录。</li>
 * </ol>
 *
 * <h3>为什么记录也要蹲下</h3>
 * 激光屏的**不蹲下右键**是"打开面板"（编排四台激光），如果记录也占用不蹲下，
 * 玩家一想开面板就会顺手把手里的激光再记一遍。蹲下把两个动作分开，
 * 和遥控器、密码连接器的既有手感一致。
 *
 * <h3>为什么不做网络包</h3>和遥控器同理：右键方块的事件本身就是双端调用的，
 * 服务端写物品 NBT，客户端经由常规物品栏同步拿到，少一个包、少一类不一致。
 */
public class ItemLaserConnector extends Item {

    // ---- 设备列表（多个，索引式） ----
    private static final String K_COUNT = "LaserDevCount";
    private static final String K_DEV = "LaserDev";

    /** 最多记几台。攒再多也没用：一块激光屏总共才 {@link LaserLayout#SLOTS} 个槽 */
    public static final int MAX_DEVICES = LaserLayout.SLOTS;

    /**
     * 蹲下右键时，若点到的那一格读不出设备，就往周围扫几格。
     *
     * 【为什么是 2 而不是遥控器的 3】激光部件（稳定器/发射器/接收器/喷射器）都是
     * 标准大小的方块，没有"流体桶放大模型""9x9 叶片"那种点不到的情况；
     * 扫太远反而会把隔壁那台也记进来，玩家分不清记的是哪一台。
     */
    public static final int DEVICE_SCAN_RADIUS = 2;

    public ItemLaserConnector() {
        this.setRegistryName("jiezhimod", "laser_connector");
        this.setUnlocalizedName("laser_connector");
        this.setMaxStackSize(1);
    }

    // ==================== 右键 ====================

    /**
     * 【不蹲下一律 PASS】把"打开面板"完整让给方块。
     *
     * 这里返回 PASS 而不是 SUCCESS 是关键：{@code ItemStack.onItemUse} 返回 SUCCESS
     * 之后 {@code Block.onBlockActivated} 根本不会被调用 —— 手里拿着链接器就再也
     * 打不开激光屏的面板了（而且一点提示都没有，玩家只会以为屏坏了）。
     */
    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!player.isSneaking()) {
            return EnumActionResult.PASS;
        }
        // 蹲下之后的动作全是服务端逻辑；客户端只回"这一下我处理了"
        if (world.isRemote) {
            return EnumActionResult.SUCCESS;
        }
        ItemStack stack = player.getHeldItem(hand);

        // A. 蹲下右键激光屏本身 = 把链接器里记录到的设备一次接进去。
        //    （面板里的「添加」按钮走的是同一个 import，两条入口一套语义）
        TileLaserScreen screen = laserScreenAt(world, pos);
        if (screen != null) {
            connect(stack, screen, player);
            return EnumActionResult.SUCCESS;
        }

        // B. 蹲下右键一台激光 = 记录
        TileEntity dev = DfcBridge.findDeviceAt(world, pos, DEVICE_SCAN_RADIUS);
        if (dev != null) {
            BlockPos dpos = dev.getPos();
            int kind = DfcBridge.laserKind(dev);
            boolean added = addDevice(stack, dpos, world.provider.getDimension());
            int n = deviceCount(stack);
            String what = kind == LaserInfo.KIND_NONE
                    ? DfcBridge.deviceName(dev)
                    : LaserLayout.labelPrefix(kind) + " " + LaserLayout.kindName(kind);
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + (added
                                    ? TextFormatting.GREEN + "已记录 " + what + " @ " + pos(dpos)
                                    : TextFormatting.YELLOW + "已经记过这台：" + what)
                            + TextFormatting.GRAY + "  (" + n + "/" + MAX_DEVICES + ")"
                            + TextFormatting.DARK_GRAY + "  蹲下右键激光屏 = 全部接入"), false);
            return EnumActionResult.SUCCESS;
        }

        // C. 蹲下但周围没有可读设备：明确回一句，别让玩家以为"蹲下右键毫无反应"
        player.sendStatusMessage(new TextComponentString(
                TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                        + TextFormatting.YELLOW + "附近 " + DEVICE_SCAN_RADIUS
                        + " 格内没有可读的激光部件"), true);
        return EnumActionResult.SUCCESS;
    }

    /** 蹲下右键空气 = 清空记录 */
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!player.isSneaking()) {
            return new ActionResult<ItemStack>(EnumActionResult.PASS, stack);
        }
        if (!world.isRemote) {
            clearDevices(stack);
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.YELLOW + "激光链接器已清空。"), true);
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
    }

    // ==================== 接入 ====================

    /** 点到的那一格（或它所属的多方块）是不是激光状态显示器；是就返回它的方块实体 */
    @Nullable
    private static TileLaserScreen laserScreenAt(World world, BlockPos pos) {
        BlockPos cp = ScreenMultiblock.findController(world, pos);
        if (cp == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(cp);
        return te instanceof TileLaserScreen ? (TileLaserScreen) te : null;
    }

    /** 把记录到的设备全部接进屏幕，并回一句人话 */
    private static void connect(ItemStack stack, TileLaserScreen screen, EntityPlayer player) {
        if (deviceCount(stack) <= 0) {
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.YELLOW
                            + "链接器里还没记录设备。蹲下右键一台激光部件先记下来。"), false);
            return;
        }
        int[] r = screen.importDevices(stack);
        screen.refresh();
        player.sendStatusMessage(new TextComponentString(
                TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                        + summarize(r, screen.getSlots().size())), false);
    }

    /** 导入结果拼成一句人话（面板「添加」与蹲下右键两条路共用，措辞保持一致） */
    public static String summarize(int[] r, int used) {
        StringBuilder sb = new StringBuilder();
        sb.append(TextFormatting.GREEN).append("已接入 ").append(r[0]).append(" 台");
        if (r[1] > 0) {
            sb.append(TextFormatting.YELLOW).append("；").append(r[1])
                    .append(" 台跳过（已满或已经接过）");
        }
        if (r[2] > 0) {
            sb.append(TextFormatting.RED).append("；").append(r[2]).append(" 台找不到");
        }
        sb.append(TextFormatting.GRAY).append("  (").append(used)
                .append("/").append(MAX_DEVICES).append(" 个槽)");
        return sb.toString();
    }

    // ==================== 设备列表 NBT ====================

    /**
     * 记录一台设备。同一台（同坐标同维度）只留一份 —— 手抖点两下不该占两个槽。
     *
     * @return 是否真的记下了（false = 已经记过 / 列表已满）
     */
    public static boolean addDevice(ItemStack stack, BlockPos pos, int dim) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        int n = tag.getInteger(K_COUNT);
        for (int i = 0; i < n; i++) {
            String p = K_DEV + i + "_";
            if (tag.getInteger(p + "X") == pos.getX()
                    && tag.getInteger(p + "Y") == pos.getY()
                    && tag.getInteger(p + "Z") == pos.getZ()
                    && tag.getInteger(p + "Dim") == dim) {
                return false;
            }
        }
        if (n >= MAX_DEVICES) {
            return false;
        }
        String p = K_DEV + n + "_";
        tag.setInteger(p + "X", pos.getX());
        tag.setInteger(p + "Y", pos.getY());
        tag.setInteger(p + "Z", pos.getZ());
        tag.setInteger(p + "Dim", dim);
        tag.setInteger(K_COUNT, n + 1);
        return true;
    }

    public static int deviceCount(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) {
            return 0;
        }
        return Math.min(MAX_DEVICES, Math.max(0, stack.getTagCompound().getInteger(K_COUNT)));
    }

    /** 第 i 台设备的坐标（越界/损坏返回 null） */
    @Nullable
    public static BlockPos deviceAt(ItemStack stack, int index) {
        if (stack == null || !stack.hasTagCompound() || index < 0 || index >= deviceCount(stack)) {
            return null;
        }
        NBTTagCompound t = stack.getTagCompound();
        String p = K_DEV + index + "_";
        if (!t.hasKey(p + "X")) {
            return null;
        }
        return new BlockPos(t.getInteger(p + "X"), t.getInteger(p + "Y"), t.getInteger(p + "Z"));
    }

    /** 第 i 台设备记录时所处的维度（越界返回 {@link Integer#MIN_VALUE}，永远不等于任何真实维度） */
    public static int deviceDim(ItemStack stack, int index) {
        if (stack == null || !stack.hasTagCompound() || index < 0 || index >= deviceCount(stack)) {
            return Integer.MIN_VALUE;
        }
        return stack.getTagCompound().getInteger(K_DEV + index + "_Dim");
    }

    public static void clearDevices(ItemStack stack) {
        NBTTagCompound tag = stack == null ? null : stack.getTagCompound();
        if (tag == null) {
            return;
        }
        int n = tag.getInteger(K_COUNT);
        for (int i = 0; i < n; i++) {
            String p = K_DEV + i + "_";
            tag.removeTag(p + "X");
            tag.removeTag(p + "Y");
            tag.removeTag(p + "Z");
            tag.removeTag(p + "Dim");
        }
        tag.removeTag(K_COUNT);
    }

    private static String pos(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    // ==================== 提示 ====================

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
                               ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.laser_connector.0"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.laser_connector.1"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.laser_connector.2"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.laser_connector.3",
                deviceCount(stack), MAX_DEVICES));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.laser_connector.4"));
    }
}
