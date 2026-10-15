package com.jiezhimod.client;

import com.jiezhimod.countdown.Countdown;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端倒计时显示数据：从服务端同步包整体替换，本地每 tick 平滑递减。
 */
public class ClientCountdownManager {
    private static final List<Countdown> COUNTDOWNS = new ArrayList<>();

    public static void setFromServer(List<Countdown> serverList) {
        COUNTDOWNS.clear();
        if (serverList != null) {
            COUNTDOWNS.addAll(serverList);
        }
    }

    public static void onClientTick() {
        for (Countdown c : COUNTDOWNS) {
            if (!c.paused && !c.finished && c.remainingTicks > 0) {
                c.remainingTicks--;
                if (c.remainingTicks <= 0) {
                    c.remainingTicks = 0;
                    c.finished = true;
                }
            }
        }
    }

    public static List<Countdown> getList() {
        return COUNTDOWNS;
    }
}
