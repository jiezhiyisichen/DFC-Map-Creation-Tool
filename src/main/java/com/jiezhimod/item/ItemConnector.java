package com.jiezhimod.item;

import com.jiezhimod.block.TileKeypad;
import com.jiezhimod.block.TilePasswordConsole;
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
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/**
 * 密码连接器：把**密码盘**和**密码控制台**绑到一起。
 *
 * 用法（都在世界里完成，没有界面）：
 *   1. **蹲下右键密码盘** → 连接器记住它的坐标（聊天栏会提示）；
 *   2. **蹲下右键控制台** → 把记住的密码盘写进控制台；
 *   3. 不蹲下右键控制台 → 打开控制台的配置界面。
 *   4. 蹲下右键空气 → 清空连接器里记的坐标。
 *
 * 【为什么用专用物品，不复用 DFC 遥控器】遥控器那套 NBT 是"DFC 读数设备列表"，
 * 语义和密码盘完全不同；混进去会让 {@code importDevices} 认错东西。
 * 一个只存一个坐标的小物品更干净。
 */
public class ItemConnector extends Item {

    private static final String TAG_X = "KeypadX";
    private static final String TAG_Y = "KeypadY";
    private static final String TAG_Z = "KeypadZ";
    private static final String TAG_DIM = "KeypadDim";

    /** 允许绑定的最远距离（格）。超出说明玩家在乱点 / 改包 */
    private static final double MAX_LINK_DISTANCE = 32.0D;

    public ItemConnector() {
        this.setRegistryName("jiezhimod", "connector");
        this.setUnlocalizedName("connector");
        this.setMaxStackSize(1);
        this.setCreativeTab(ModItems.TAB);
    }

    // ==================== NBT ====================

    private static BlockPos read(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(TAG_X)) {
            return null;
        }
        return new BlockPos(tag.getInteger(TAG_X), tag.getInteger(TAG_Y), tag.getInteger(TAG_Z));
    }

    private static Integer readDim(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && tag.hasKey(TAG_DIM) ? tag.getInteger(TAG_DIM) : null;
    }

    private static void write(ItemStack stack, BlockPos pos, int dim) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setInteger(TAG_X, pos.getX());
        tag.setInteger(TAG_Y, pos.getY());
        tag.setInteger(TAG_Z, pos.getZ());
        tag.setInteger(TAG_DIM, dim);
    }

    private static void clear(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            return;
        }
        tag.removeTag(TAG_X);
        tag.removeTag(TAG_Y);
        tag.removeTag(TAG_Z);
        tag.removeTag(TAG_DIM);
    }

    // ==================== 交互 ====================

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!player.isSneaking()) {
            // 不蹲下就交给方块自己处理（控制台要开界面）
            return EnumActionResult.PASS;
        }
        if (world.isRemote) {
            return EnumActionResult.SUCCESS;
        }

        ItemStack stack = player.getHeldItem(hand);
        TileEntity te = world.getTileEntity(pos);

        if (te instanceof TileKeypad) {
            write(stack, pos, world.provider.getDimension());
            player.sendStatusMessage(new TextComponentTranslation(
                    "msg.jiezhimod.connector.recorded",
                    pos.getX(), pos.getY(), pos.getZ()), true);
            return EnumActionResult.SUCCESS;
        }

        if (te instanceof TilePasswordConsole) {
            BlockPos keypad = read(stack);
            Integer dim = readDim(stack);
            if (keypad == null) {
                player.sendStatusMessage(new TextComponentTranslation(
                        "msg.jiezhimod.connector.empty"), true);
                return EnumActionResult.SUCCESS;
            }
            if (dim == null || dim != world.provider.getDimension()) {
                player.sendStatusMessage(new TextComponentTranslation(
                        "msg.jiezhimod.connector.wrongdim"), true);
                return EnumActionResult.SUCCESS;
            }
            if (player.getDistanceSq(keypad.getX() + 0.5D, keypad.getY() + 0.5D, keypad.getZ() + 0.5D)
                    > MAX_LINK_DISTANCE * MAX_LINK_DISTANCE) {
                player.sendStatusMessage(new TextComponentTranslation(
                        "msg.jiezhimod.connector.toofar"), true);
                return EnumActionResult.SUCCESS;
            }
            if (!(world.getTileEntity(keypad) instanceof TileKeypad)) {
                player.sendStatusMessage(new TextComponentTranslation(
                        "msg.jiezhimod.connector.gone"), true);
                return EnumActionResult.SUCCESS;
            }
            ((TilePasswordConsole) te).setKeypadPos(keypad);
            player.sendStatusMessage(new TextComponentTranslation(
                    "msg.jiezhimod.connector.linked",
                    keypad.getX(), keypad.getY(), keypad.getZ()), true);
            return EnumActionResult.SUCCESS;
        }

        return EnumActionResult.PASS;
    }

    /** 蹲下右键空气：清掉记录 */
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!player.isSneaking()) {
            return new ActionResult<ItemStack>(EnumActionResult.PASS, stack);
        }
        RayTraceResult hit = player.rayTrace(5.0D, 1.0F);
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            // 指着方块时让 onItemUse 处理
            return new ActionResult<ItemStack>(EnumActionResult.PASS, stack);
        }
        if (!world.isRemote) {
            clear(stack);
            player.sendStatusMessage(new TextComponentTranslation(
                    "msg.jiezhimod.connector.cleared"), true);
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
    }

    // ==================== tooltip ====================

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, World world, List<String> tip, ITooltipFlag flag) {
        BlockPos pos = read(stack);
        if (pos == null) {
            tip.add(I18n.format("tooltip.jiezhimod.connector.0"));
        } else {
            tip.add(I18n.format("tooltip.jiezhimod.connector.1", pos.getX(), pos.getY(), pos.getZ()));
        }
        tip.add(I18n.format("tooltip.jiezhimod.connector.2"));
        tip.add(I18n.format("tooltip.jiezhimod.connector.3"));
    }
}
