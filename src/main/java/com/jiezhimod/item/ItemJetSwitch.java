package com.jiezhimod.item;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 喷气开关（Jet Switch）—— 手持物，配合已穿戴的喷气背包使用。
 *
 * 交互：按住右键起飞，松开立刻失去动力（喷气粒子随之停止）。
 * 本类只做"能被识别为开关"这一件事，飞行逻辑全在 {@link JetpackEffect}（服务端权威）。
 * 热量条由 {@link JetHeatBarRenderer} 在客户端渲染。
 *
 * 【★ 为什么必须覆写 onItemRightClick + getMaxItemUseDuration】
 * 这是"右键飞不起来"的根因。飞行判定读的是 {@code player.isHandActive()}
 * （= 官方定义的"正按住右键"状态），而这个状态**只能**由物品调用
 * {@code player.setActiveHand(hand)} 建立。原版 {@code Item.onItemRightClick}
 * 默认返回 PASS、什么都不做，所以这个开关从来没进入过"持用中"状态 ——
 * 右键点一百下也不会喷一下。
 *
 * 更隐蔽的是：{@code EntityLivingBase.setActiveHand} 里第一件事就是
 * <pre>
 *   int i = ForgeEventFactory.onItemUseStart(this, stack, stack.getMaxItemUseDuration());
 *   if (i &lt;= 0) return;      // ← 时长为 0 直接放弃，连状态位都不置
 * </pre>
 * 而 {@code Item.getMaxItemUseDuration} 默认返回 **0**。所以光调 setActiveHand 还不够，
 * 必须同时把持用时长给成一个正数（这里取 72000，与弓同款：够长，真正的结束靠松开右键）。
 */
public class ItemJetSwitch extends Item {

    /** 持用时长上限（tick）。1 小时，实际永远由"松开右键"提前结束，与弓一致 */
    public static final int MAX_USE_TICKS = 72000;

    public ItemJetSwitch() {
        this.setRegistryName("jiezhimod", "jet_switch");
        this.setUnlocalizedName("jet_switch");
        this.setMaxStackSize(1);
        // 开关不参与耐久/燃烧等 vanilla 消耗机制
        this.setMaxDamage(0);
    }

    /**
     * 右键按下：进入"持用中"状态（这就是"按住右键"）。
     *
     * 双端都会走到这里：客户端负责本机手感，服务端收到 CPacketPlayerTryUseItem 后
     * 也会跑一遍，于是服务端那份 isHandActive() 同样成立 —— 权威判定由此建立。
     */
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        player.setActiveHand(hand);
        // 返回 SUCCESS 才会让客户端把"开始使用"同步给服务端
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
    }

    /** 见类注释：返回 0 的话 setActiveHand 会直接放弃 */
    @Override
    public int getMaxItemUseDuration(ItemStack stack) {
        return MAX_USE_TICKS;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(TextFormatting.AQUA + I18n.format("tooltip.jiezhimod.jet_switch.0"));
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.jet_switch.1"));
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.jiezhimod.jet_switch.2"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.jiezhimod.jet_switch.3"));
    }
}
