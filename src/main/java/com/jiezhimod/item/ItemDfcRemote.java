package com.jiezhimod.item;

import com.jiezhimod.block.ScreenMultiblock;
import com.jiezhimod.block.TileCustomScreen;
import com.jiezhimod.block.TileDfcScreen;
import com.jiezhimod.dfc.DfcBridge;
import com.jiezhimod.dfc.DfcWatch;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.client.resources.I18n;
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

import javax.annotation.Nullable;
import java.util.List;

/**
 * DFC 遥控器：把"数据"和"显示屏"配起来的小工具。
 *
 * 现在管两种活：
 *
 * **A. 反应堆（原有）**——给核心屏 / 部件监视器用
 *   1. 右键 DFC 核心 或 DFC 稳定器 -> 记下那个坐标；
 *   2. 拿着它右键显示器 -> 显示器绑定到这个坐标，并立刻回读一次数据；
 *
 * **B. 外部设备（新增）**——给自定义显示屏用
 *   1. **蹲下右键**一台储罐 / 电池 -> 记下这台设备；
 *   2. **蹲下右键**自定义显示屏 -> 把记录到的设备全部接进去（每台设备有几项读数
 *      就占几个数据槽，总共最多 {@link DfcWatch#MAX} 条）；
 *   3. 蹲下右键空气 -> 清空全部记录。
 *
 * 【为什么设备这条要用"蹲下"】自定义屏的**右键不蹲下**是"打开面板"（要编排 8 条数据），
 * 如果连接也占用不蹲下，玩家一想开面板就会顺手把设备接进去。蹲下把两个动作分开，
 * 也和"蹲下右键空气 = 清空"的既有手感一路。
 *
 * 【为什么不做网络包】绑定全程在服务端完成即可：右键方块的事件本身就是双端调用的，
 * 服务端写物品 NBT，客户端会经由常规的物品栏同步拿到（物品栏里的 NBT 是自动同步的）。
 * 少一个包、少一类"客户端先斩后奏"的不一致。
 */
public class ItemDfcRemote extends Item {

    // ---- 反应堆源（单个） ----
    private static final String K_X = "DfcSrcX";
    private static final String K_Y = "DfcSrcY";
    private static final String K_Z = "DfcSrcZ";
    private static final String K_DIM = "DfcSrcDim";
    private static final String K_NAME = "DfcSrcName";

    // ---- 设备列表（多个，索引式） ----
    private static final String K_DEV_COUNT = "DfcDevCount";
    private static final String K_DEV = "DfcDev";
    /** 最多先攒多少台设备。攒再多也没用：一块屏总共才 8 条数据 */
    public static final int MAX_DEVICES = DfcWatch.MAX;

    /**
     * 蹲下右键时，若点到的那一格读不出数据，就往周围扫几格找设备。
     *
     * 【为什么是 3】两类"点不到"的距离不一样：
     *   - 大模型方块（流体桶把模型放大到好几格）—— 准心离真正的方块 1~2 格；
     *   - 模块化涡轮的叶片/飞轮是 9x9 的大平面，只有正中间那一格挂 TE，
     *     站在边上右键时离核心能到 3 格。
     * 取最近的一台，并且提示里会报出设备名和坐标 —— 万一记错了玩家一眼就能看出来。
     */
    public static final int DEVICE_SCAN_RADIUS = 3;

    public ItemDfcRemote() {
        this.setRegistryName("jiezhimod", "dfc_remote");
        this.setUnlocalizedName("dfc_remote");
        this.setMaxStackSize(1);
        this.setCreativeTab(ModItems.TAB);
    }

    // ==================== 右键方块 ====================

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return EnumActionResult.SUCCESS;
        }
        TileEntity te = DfcBridge.resolveProxy(world.getTileEntity(pos));
        ItemStack stack = player.getHeldItem(hand);

        // A. 反应堆部件（核心 / 稳定器）：无视是否蹲下，都按"记录反应堆源"处理 ——
        //    这两样是核心屏的数据源头，语义和"外部设备"完全不同，优先判掉。
        if (DfcBridge.isDataSource(te)) {
            boolean core = DfcBridge.isDfcCore(te);
            BlockPos dpos = te.getPos();
            writeTarget(stack, dpos, world.provider.getDimension(), core ? "CORE" : "STABILIZER");
            BlockPos resolved = DfcBridge.resolveCore(world, dpos);
            String tail = resolved == null
                    ? TextFormatting.RED + " (no core resolved)"
                    : TextFormatting.GRAY + " -> core " + pos(resolved);
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + "Recorded " + (core ? "CORE" : "STABILIZER") + " " + pos(dpos)
                            + tail), false);
            return EnumActionResult.SUCCESS;
        }

        // B. 外部设备（储罐 / 电池 / 飞轮…）：**必须蹲下**才记。
        //    不蹲下时把事件让给方块自己 —— 否则站在自定义屏前面想开面板，
        //    会先把屏幕当成"设备"记一遍。
        //
        //    【为什么要 findDeviceAt 而不是直接用这一格的 TE】HBM 的大机器有两种"点不到"：
        //      - 大模型方块（流体桶把模型放大到好几格），准心落在桶身上时射线打的其实是背后的方块；
        //      - 多方块外壳格（作者按 meta 决定建不建 TE，很多外壳格压根没有 TE）。
        //    findDeviceAt 会先代理解链、再就近扫 DEVICE_SCAN_RADIUS 格，
        //    所以"对着桶身/叶片右键"也能连上。记的是**设备自己的坐标**，不是被点到的那一格。
        if (player.isSneaking()) {
            TileEntity dev = DfcBridge.findDeviceAt(world, pos, DEVICE_SCAN_RADIUS);
            if (dev != null) {
                String name = DfcBridge.deviceName(dev);
                BlockPos dpos = dev.getPos();
                boolean added = addDevice(stack, dpos, world.provider.getDimension());
                int n = deviceCount(stack);
                player.sendStatusMessage(new TextComponentString(
                        TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                                + (added
                                        ? TextFormatting.GREEN + "Recorded device " + name + " @ " + pos(dpos)
                                        : TextFormatting.YELLOW + "Already recorded: " + name)
                                + TextFormatting.GRAY + "  (" + n + "/" + MAX_DEVICES + ")"
                                + TextFormatting.DARK_GRAY + "  sneak + right-click the display to hook up"),
                        false);
                return EnumActionResult.SUCCESS;
            }

            // C. 蹲下但周围确实没有可读设备。
            //    以前这里直接 PASS、什么都不说，玩家只看到"蹲下右击毫无反应"，
            //    分不清是"离太远"、"方块不支持"还是"mod 坏了"。这里明确回一句，
            //    并且把"附近没东西"和"这方块读不出数据"分开说 —— 两者的下一步动作不一样。
            String why = te == null
                    ? "no readable device within " + DEVICE_SCAN_RADIUS + " blocks"
                    : "unsupported block: " + te.getClass().getSimpleName()
                            + " (no readable tank / energy / spin data)";
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.YELLOW + "Nothing to record - " + why), true);
            return EnumActionResult.SUCCESS;
        }

        // 不是 DFC 部件：别抢事件，让方块自己去处理（比如右键显示器开 GUI / 开关）
        return EnumActionResult.PASS;
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!player.isSneaking()) {
            return new ActionResult<ItemStack>(EnumActionResult.PASS, stack);
        }
        if (!world.isRemote) {
            clearTarget(stack);
            clearDevices(stack);
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.YELLOW + "Remote cleared (source + devices)."), true);
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
    }

    // ==================== 绑定到显示器 ====================

    /**
     * 由 {@link ScreenMultiblock#handleUse} 调用。
     *
     * 右键的可能不是控制器那一格（玩家往往点到屏幕中间），所以先扫出控制器。
     *
     * @return 是否消耗了这次右键（true = 当作处理过了，不再切换开关 / 不再开 GUI）
     */
    public boolean useOnScreen(ItemStack stack, World world, BlockPos pos, EntityPlayer player) {
        if (world.isRemote) {
            return true;   // 逻辑在服务端，客户端只负责"我这下点了东西"
        }
        BlockPos controllerPos = ScreenMultiblock.findController(world, pos);
        if (controllerPos == null) {
            return false;
        }
        TileEntity controller = world.getTileEntity(controllerPos);
        if (!(controller instanceof TileDfcScreen)) {
            return true;
        }

        // 自定义屏 -> 接入记录到的设备
        if (controller instanceof TileCustomScreen) {
            return connectDevices(stack, (TileCustomScreen) controller, world, player);
        }

        // 核心屏 / 监视器 -> 绑定反应堆源（原有逻辑，手感不变）
        if (!hasTarget(stack)) {
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.YELLOW
                            + "Nothing recorded. Right-click a DFC core or stabilizer first."), false);
            return true;
        }
        int dim = stack.getTagCompound().getInteger(K_DIM);
        if (dim != world.provider.getDimension()) {
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.RED + "Recorded source is in another dimension."), false);
            return true;
        }
        BlockPos src = readTarget(stack);
        ((TileDfcScreen) controller).bindSource(src, player);
        return true;
    }

    /**
     * 把遥控器里记录到的设备逐台接进自定义屏。
     *
     * 【这里只负责提示，真正的导入在 {@link TileCustomScreen#importDevices}】
     * GUI 的「添加」按钮走的是同一个方法 —— 两条入口一套语义，
     * 不会出现"右键接入提示 3 条、GUI 添加却只进 2 条"这种对不上。
     */
    private boolean connectDevices(ItemStack stack, TileCustomScreen screen, World world, EntityPlayer player) {
        if (deviceCount(stack) <= 0) {
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.YELLOW
                            + "No device recorded. Sneak + right-click a tank / battery first."), false);
            return true;
        }
        int[] r = screen.importDevices(stack);
        screen.refresh();
        player.sendStatusMessage(new TextComponentString(
                TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                        + summarize(r, screen.getSlots().size())), false);
        return true;
    }

    /** 把导入结果拼成一句人话（GUI 与右键两条路共用，措辞保持一致） */
    public static String summarize(int[] r, int used) {
        StringBuilder sb = new StringBuilder();
        sb.append(TextFormatting.GREEN).append("Hooked up ").append(r[0]).append(" reading(s)");
        if (r[1] > 0) {
            sb.append(TextFormatting.YELLOW).append("; ").append(r[1])
                    .append(" skipped (panel full or already listed)");
        }
        if (r[2] > 0) {
            sb.append(TextFormatting.RED).append("; ").append(r[2]).append(" not found");
        }
        sb.append(TextFormatting.GRAY).append("  (").append(used)
                .append("/").append(DfcWatch.MAX).append(" slots used)");
        return sb.toString();
    }

    // ==================== 反应堆源 NBT ====================

    public static void writeTarget(ItemStack stack, BlockPos pos, int dim, String kind) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setInteger(K_X, pos.getX());
        tag.setInteger(K_Y, pos.getY());
        tag.setInteger(K_Z, pos.getZ());
        tag.setInteger(K_DIM, dim);
        tag.setString(K_NAME, kind);
    }

    public static void clearTarget(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null) {
            tag.removeTag(K_X);
            tag.removeTag(K_Y);
            tag.removeTag(K_Z);
            tag.removeTag(K_DIM);
            tag.removeTag(K_NAME);
        }
    }

    public static boolean hasTarget(ItemStack stack) {
        return stack.hasTagCompound() && stack.getTagCompound().hasKey(K_X);
    }

    @Nullable
    public static BlockPos readTarget(ItemStack stack) {
        if (!hasTarget(stack)) {
            return null;
        }
        NBTTagCompound t = stack.getTagCompound();
        return new BlockPos(t.getInteger(K_X), t.getInteger(K_Y), t.getInteger(K_Z));
    }

    public static String readKind(ItemStack stack) {
        if (!stack.hasTagCompound()) {
            return "";
        }
        return stack.getTagCompound().getString(K_NAME);
    }

    // ==================== 设备列表 NBT ====================

    /**
     * 记录一台设备。
     *
     * 同一台设备重复记录会被忽略（同坐标同维度只留一份），否则玩家手抖点两下，
     * 绑定的时候会白占两个槽位。
     *
     * @return 是否真的记下了（false = 已经记过 / 列表已满）
     */
    public static boolean addDevice(ItemStack stack, BlockPos pos, int dim) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        int n = tag.getInteger(K_DEV_COUNT);
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
        tag.setInteger(K_DEV_COUNT, n + 1);
        return true;
    }

    public static int deviceCount(ItemStack stack) {
        if (!stack.hasTagCompound()) {
            return 0;
        }
        return Math.min(MAX_DEVICES, Math.max(0, stack.getTagCompound().getInteger(K_DEV_COUNT)));
    }

    /**
     * 第 i 台记录到的设备的坐标（越界/损坏返回 null）。
     *
     * 【为什么要开这两个 getter】导入流程现在有**两条入口**：老的手感（蹲下右键本屏）
     * 和新的 GUI（把遥控器放进槽位点「添加」）。两边必须看到同一份数据，
     * 所以把"NBT 怎么读"收在这里，{@code TileCustomScreen.importDevices} 只管用。
     */
    @Nullable
    public static BlockPos deviceAt(ItemStack stack, int index) {
        if (!stack.hasTagCompound() || index < 0 || index >= deviceCount(stack)) {
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
        if (!stack.hasTagCompound() || index < 0 || index >= deviceCount(stack)) {
            return Integer.MIN_VALUE;
        }
        return stack.getTagCompound().getInteger(K_DEV + index + "_Dim");
    }

    public static void clearDevices(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            return;
        }
        int n = tag.getInteger(K_DEV_COUNT);
        for (int i = 0; i < n; i++) {
            String p = K_DEV + i + "_";
            tag.removeTag(p + "X");
            tag.removeTag(p + "Y");
            tag.removeTag(p + "Z");
            tag.removeTag(p + "Dim");
        }
        tag.removeTag(K_DEV_COUNT);
    }

    private static String pos(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    // ==================== 提示 ====================

    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        if (!hasTarget(stack)) {
            tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.dfc_remote.0"));
            tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.dfc_remote.1"));
        } else {
            BlockPos p = readTarget(stack);
            tooltip.add(TextFormatting.GREEN + I18n.format("tooltip.jiezhimod.dfc_remote.2",
                    readKind(stack), p == null ? "?" : pos(p)));
        }
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_remote.3",
                deviceCount(stack), MAX_DEVICES));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.dfc_remote.4"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_remote.5"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.dfc_remote.6"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.dfc_remote.7"));
    }
}
