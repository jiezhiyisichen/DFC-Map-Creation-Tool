package com.jiezhimod.item;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/**
 * 玩家登出时清理喷气背包的热量状态。
 *
 * 为什么要单独一个类：JetpackEffect 里的 HEAT_MAP 是静态 Map，
 * 不清理的话每次玩家重进服务器都会多一份，久了就是内存泄漏。
 *
 * 【导包注意】登出/换维度事件在 1.12.2 属于
 * net.minecraftforge.fml.common.gameevent.PlayerEvent，
 * **不是** net.minecraftforge.event.entity.player.PlayerEvent（那里没有这两个类），
 * 与项目里 ServerCountdownManager 的用法保持一致。
 */
public class JetpackLogoutHandler {

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // 1.12.2 的 PlayerEvent 用 public final 字段 player（没有 getPlayer() 方法）
        if (event.player != null) {
            JetpackEffect.forget(event.player.getUniqueID());
        }
    }

    /** 重进维度也要清：换维度时玩家实体会被重建 */
    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player != null) {
            JetpackEffect.forget(event.player.getUniqueID());
        }
    }
}
