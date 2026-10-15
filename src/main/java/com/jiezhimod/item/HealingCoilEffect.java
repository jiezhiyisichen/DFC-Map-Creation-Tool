package com.jiezhimod.item;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * 治愈线圈生效逻辑：手持时每秒回复 4 点生命（= 2 颗心）。
 *
 * 为什么不用「生命恢复」药水效果：直接调用 EntityPlayer.heal() 才是干净的治疗，
 * 没有药水粒子、没有 HUD 图标、不占用效果栏；而且 heal() 是加法语义，
 * 与玩家自己喝的再生药水叠加时会各加各的，不会互相覆盖 —— 符合「效果在线圈基础上再进一步」。
 *
 * 只在服务端治疗：生命值是服务端权威，客户端血量由 EntityPlayerMP.onUpdateEntity()
 * 检测到 getHealth() 与 lastHealth 不一致后发 SPacketUpdateHealth 同步；
 * 若两端都 heal，客户端会先被本地加血、再被服务端包覆盖，出现血条闪动。
 */
public class HealingCoilEffect {

    /** 每次治疗量：4 点生命 = 2 颗心 */
    public static final float HEAL_PER_SECOND = 4.0F;

    /** 治疗间隔（tick）：20 tick = 1 秒 */
    public static final int INTERVAL_TICKS = 20;

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        EntityPlayer player = event.player;
        if (player == null || player.world == null) {
            return;
        }
        if (!shouldHealThisTick(player.ticksExisted, CoilUtil.isHolding(player, ModItems.HEALING_COIL),
                player.world.isRemote, player.getHealth(), player.getMaxHealth())) {
            return;
        }
        player.heal(HEAL_PER_SECOND);
    }

    /**
     * 这一 tick 是否应当治疗。
     *
     * 抽成纯函数是为了能离线自测（真实判定要在实体上跑）。
     * 不再治疗的情况：客户端（生命值服务端权威）、未到整秒、没拿线圈、已死亡、已满血。
     */
    public static boolean shouldHealThisTick(int ticksExisted, boolean holding, boolean remote,
                                             float health, float maxHealth) {
        return !remote
                && ticksExisted % INTERVAL_TICKS == 0
                && holding
                && health > 0.0F
                && health < maxHealth;
    }
}
