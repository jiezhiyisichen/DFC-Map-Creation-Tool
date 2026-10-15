package com.jiezhimod.countdown;

import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageCountdownSync;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端倒计时管理：存储、tick 递减、状态变更广播、玩家加入同步。
 * 注册到 MinecraftForge.EVENT_BUS，ServerTickEvent / PlayerLoggedInEvent 仅在服务端（含集成服务器）触发。
 */
public class ServerCountdownManager {
    private static final Map<String, Countdown> COUNTDOWNS = new LinkedHashMap<>();
    private static int tickCounter = 0;
    /** 上一次广播时是否有内容：用于在没有倒计时后只补发一次清空包，避免每秒空包刷屏 */
    private static boolean lastNonEmpty = false;

    public static void clear() {
        COUNTDOWNS.clear();
        lastNonEmpty = false;
    }

    public static boolean exists(String name) {
        return COUNTDOWNS.containsKey(name);
    }

    /** 已有倒计时名称列表（用于指令 Tab 补全） */
    public static List<String> names() {
        return Collections.unmodifiableList(new ArrayList<>(COUNTDOWNS.keySet()));
    }

    /** @return true 添加/覆盖成功 */
    public static boolean add(String name, int style, float r, float g, float b, long seconds) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String key = name.trim();
        Countdown c = new Countdown(key, style, r, g, b, Math.max(1L, seconds) * 20L);
        COUNTDOWNS.put(key, c);
        broadcast();
        return true;
    }

    public static boolean remove(String name) {
        Countdown c = COUNTDOWNS.remove(name);
        if (c != null) {
            broadcast();
            return true;
        }
        return false;
    }

    /** @return 暂停/继续是否成功 */
    public static boolean setPaused(String name, boolean paused) {
        Countdown c = COUNTDOWNS.get(name);
        if (c == null) {
            return false;
        }
        if (c.paused != paused) {
            c.paused = paused;
            broadcast();
        }
        return true;
    }

    /** @return 剩余整秒；不存在返回 -1 */
    public static long getRemainingSeconds(String name) {
        Countdown c = COUNTDOWNS.get(name);
        return c == null ? -1L : c.remainingTicks / 20L;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;

        boolean finishedNow = false;
        for (Countdown c : COUNTDOWNS.values()) {
            if (c.finished || c.paused || c.remainingTicks <= 0) {
                continue;
            }
            c.remainingTicks--;
            if (c.remainingTicks <= 0) {
                c.remainingTicks = 0;
                c.finished = true;
                finishedNow = true;
            }
        }

        // 归零的那一刻立刻广播一次（客户端变暗、动画停止）
        if (finishedNow) {
            broadcast();
            return;
        }
        // 每秒广播一次全量，纠正客户端本地递减的漂移
        if (!COUNTDOWNS.isEmpty() && tickCounter % 20 == 0) {
            broadcast();
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP && !COUNTDOWNS.isEmpty()) {
            ModNetwork.CHANNEL.sendTo(new MessageCountdownSync(snapshot()), (EntityPlayerMP) event.player);
        }
    }

    public static void broadcast() {
        boolean nonEmpty = !COUNTDOWNS.isEmpty();
        if (!nonEmpty && !lastNonEmpty) {
            return; // 一直为空，没必要重复广播清空
        }
        lastNonEmpty = nonEmpty;
        ModNetwork.CHANNEL.sendToAll(new MessageCountdownSync(snapshot()));
    }

    private static List<Countdown> snapshot() {
        return new ArrayList<>(COUNTDOWNS.values());
    }
}
