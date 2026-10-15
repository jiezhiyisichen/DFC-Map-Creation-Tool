package com.jiezhimod.item;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.UUID;

/**
 * 加速线圈生效逻辑：手持时给玩家的 MOVEMENT_SPEED 挂一个固定的倍率修饰符。
 *
 * 为什么用属性而不是药水/直接改 motion：
 *  - EntityPlayer.getAIMoveSpeed() 就是读 MOVEMENT_SPEED 属性值，改属性 = 真正改走路速度；
 *  - 属性修饰符由 EntityTrackerEntry 通过 SPacketEntityProperties 自动同步客户端，
 *    所以客户端自身的移动预测与服务端一致，不会出现拉扯/回弹；
 *  - 无药水粒子、无 HUD 图标、可与药水叠加（药水也是 MULTIPLY_TOTAL，乘在一起）。
 *
 * 双端都挂：服务端是权威，客户端同步数据偶发延迟时也能立刻正确预测；
 * 同步包到达时按同一 UUID 覆盖式重建，结果一致，不会重复叠加。
 */
public class SpeedCoilEffect {

    /** 速度倍率（相对基础走路速度） */
    public static final float MULTIPLIER = 5.0F;

    /** 固定 UUID：用来识别并移除自己的修饰符，不能用随机 UUID，否则会越挂越多 */
    private static final UUID MODIFIER_ID = UUID.fromString("7a1e5d2c-9b34-4f61-8c07-5d2ab3e6f901");
    private static final String MODIFIER_NAME = "jiezhimod.speed_coil";

    /** AttributeModifier 的操作码：2 = MULTIPLY_TOTAL，最终值 = 原值 * (1 + amount) */
    private static final int OP_MULTIPLY_TOTAL = 2;
    /** 想要 5 倍，amount 就要填 4（1 + 4 = 5） */
    private static final double AMOUNT = MULTIPLIER - 1.0D;

    /** 构造生效用的修饰符（自测直接拿它验证倍率数学） */
    public static AttributeModifier newModifier() {
        return new AttributeModifier(MODIFIER_ID, MODIFIER_NAME, AMOUNT, OP_MULTIPLY_TOTAL).setSaved(false);
    }

    public static UUID modifierId() {
        return MODIFIER_ID;
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        EntityPlayer player = event.player;
        if (player == null) {
            return;
        }
        IAttributeInstance speed = player.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        boolean wantActive = CoilUtil.isHolding(player, ModItems.SPEED_COIL);
        boolean isActive = speed.getModifier(MODIFIER_ID) != null;
        if (wantActive && !isActive) {
            speed.applyModifier(newModifier());
            // 只在这一刻（未持有 -> 持有）响一次；已在手上时不会反复触发
            playEquipSound(player);
        } else if (!wantActive && isActive) {
            speed.removeModifier(MODIFIER_ID);
        }
    }

    /**
     * 拿起线圈的一次性提示音。
     * 只在服务端播放：服务端 -> SPacketSoundEffect -> 附近所有客户端（含自己），
     * 这样"自己戴上"和"旁边的人戴上"都有声音；若在客户端也播一次会叠响。
     */
    public static void playEquipSound(EntityPlayer player) {
        if (player == null || player.world == null || player.world.isRemote) {
            return;
        }
        player.world.playSound(null,
                player.posX, player.posY + (double) player.getEyeHeight(), player.posZ,
                ModSounds.COIL_EQUIP, SoundCategory.PLAYERS, 1.0F, 1.0F);
    }

    /** 主手或副手拿着线圈都算生效（Roblox 里也是"装备着"就给速度） */
    public static boolean isHoldingCoil(EntityPlayer player) {
        return CoilUtil.isHolding(player, ModItems.SPEED_COIL);
    }
}
