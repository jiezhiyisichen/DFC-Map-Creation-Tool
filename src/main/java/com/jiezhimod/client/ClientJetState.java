package com.jiezhimod.client;

import net.minecraft.entity.player.EntityPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端侧的喷气状态缓存（由服务端 {@link com.jiezhimod.network.msg.MessageJetExhaust} 写入）。
 *
 * 只存"别人看不见也不需要算"的东西：是否喷气、热量比例。
 * 这些值**只读不写**于客户端逻辑之外，避免和权威值打架。
 */
public final class ClientJetState {

    private static final class Entry {
        boolean firing;
        float heatRatio;
    }

    private static final Map<UUID, Entry> MAP = new HashMap<UUID, Entry>();

    private ClientJetState() {
    }

    public static void set(UUID id, boolean firing, float heatRatio) {
        Entry e = MAP.get(id);
        if (e == null) {
            e = new Entry();
            MAP.put(id, e);
        }
        e.firing = firing;
        e.heatRatio = heatRatio;
    }

    /** 该玩家是否在喷气；未知返回 null（区别于"确定没在喷"） */
    public static Boolean isFiring(EntityPlayer player) {
        if (player == null) {
            return null;
        }
        Entry e = MAP.get(player.getUniqueID());
        return e == null ? null : Boolean.valueOf(e.firing);
    }

    /** 该玩家的热量比例 0..1；未知返回 0 */
    public static float heatRatio(EntityPlayer player) {
        if (player == null) {
            return 0.0F;
        }
        Entry e = MAP.get(player.getUniqueID());
        return e == null ? 0.0F : e.heatRatio;
    }

    public static void clear() {
        MAP.clear();
    }
}
