package com.jiezhimod.item;

import com.jiezhimod.block.BlockLightRing;
import com.jiezhimod.block.BlockRingConsole;
import com.jiezhimod.block.TileLightRing;
import com.jiezhimod.block.TileRingConsole;
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
 * 灯圈链接器（v4.4）：把灯圈和控制台连起来的一支小工具。
 *
 * <p>手感与密码连接器 / 激光链接器完全一致：</p>
 * <ol>
 *   <li><b>蹲下右键</b>一格灯圈 → 记下它（同一支只记一个，再点别的就覆盖）；</li>
 *   <li><b>蹲下右键</b>灯圈控制台 → 把记录的那格绑上去（绑的是"这一格所在的组"，
 *       组内所有灯圈一起被控制）；</li>
 *   <li><b>蹲下右键空气</b> → 清空记录。</li>
 * </ol>
 *
 * <p>【为什么绑定记的是"一格"而不是"整组"]组会随拆装变化（拆掉桥接格就分成两组），
 * 记一格坐标、绑定 / 触发时再按当时的邻居关系算组，才不会出现
 * "改了布局之后控制台指向一个已经不存在的组"。</p>
 *
 * <p>【为什么不做网络包】与另外两支链接器同理：右键事件双端都会跑，
 * 服务端写物品 NBT，客户端经物品栏同步拿到。</p>
 */
public class ItemRingConnector extends Item {

    private static final String K_X = "RingX";
    private static final String K_Y = "RingY";
    private static final String K_Z = "RingZ";
    private static final String K_HAS = "RingHas";

    public ItemRingConnector() {
        this.setRegistryName("jiezhimod", "ring_connector");
        this.setUnlocalizedName("ring_connector");
        this.setMaxStackSize(1);
    }

    /**
     * 【不蹲下一律 PASS】否则手里拿着链接器就再也打不开控制台的界面
     * （{@code onItemUse} 返回 SUCCESS 会让 {@code Block.onBlockActivated} 整个不执行）。
     */
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

        // A. 蹲下右键灯圈控制台 = 把记录的那格绑上去
        if (state.getBlock() instanceof BlockRingConsole) {
            TileEntity te = world.getTileEntity(pos);
            if (!(te instanceof TileRingConsole)) {
                return EnumActionResult.SUCCESS;
            }
            TileRingConsole console = (TileRingConsole) te;
            BlockPos ring = readRecord(stack);
            if (ring == null) {
                player.sendStatusMessage(new TextComponentString(
                        TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                                + TextFormatting.YELLOW + "链接器里还没有记录灯圈。先蹲下右键一格灯圈。"), false);
                return EnumActionResult.SUCCESS;
            }
            TileEntity rt = world.getTileEntity(ring);
            if (!(rt instanceof TileLightRing)) {
                player.sendStatusMessage(new TextComponentString(
                        TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                                + TextFormatting.RED + "记录的灯圈已不存在，请重新蹲下右键一格灯圈。"), false);
                return EnumActionResult.SUCCESS;
            }
            console.bindRing(ring);
            int members = countGroup(world, ring);
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.GREEN + "已绑定 " + ring.getX() + " " + ring.getY() + " "
                            + ring.getZ() + TextFormatting.GRAY + "  (所在组共 " + members + " 格)"), false);
            return EnumActionResult.SUCCESS;
        }

        // B. 蹲下右键灯圈 = 记录
        if (state.getBlock() instanceof BlockLightRing) {
            writeRecord(stack, pos);
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.GREEN + "已记录灯圈 " + pos.getX() + " " + pos.getY()
                            + " " + pos.getZ() + TextFormatting.GRAY + "  (蹲下右键控制台完成绑定)"), false);
            return EnumActionResult.SUCCESS;
        }

        // C. 蹲下但点到的不是灯圈 / 控制台：明说，别让玩家以为没反应
        player.sendStatusMessage(new TextComponentString(
                TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                        + TextFormatting.YELLOW + "这不是灯圈也不是灯圈控制台"), true);
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
            clearRecord(stack);
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.AQUA + "[jiezhimod] " + TextFormatting.RESET
                            + TextFormatting.YELLOW + "灯圈链接器已清空。"), true);
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

    public static void clearRecord(ItemStack stack) {
        NBTTagCompound tag = stack == null ? null : stack.getTagCompound();
        if (tag == null) {
            return;
        }
        tag.removeTag(K_HAS);
        tag.removeTag(K_X);
        tag.removeTag(K_Y);
        tag.removeTag(K_Z);
    }

    /** 那格所在的组有几格（提示用；组还没建好就按 1 算） */
    private static int countGroup(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileLightRing) {
            com.jiezhimod.ring.RingGroup g =
                    com.jiezhimod.ring.RingGroupManager.of(world).groupAt(pos);
            if (g != null) {
                return g.members.length;
            }
        }
        return 1;
    }

    // ==================== 提示 ====================

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
                               ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.ring_connector.0"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.ring_connector.1"));
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.ring_connector.2"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.ring_connector.3"));
    }
}
