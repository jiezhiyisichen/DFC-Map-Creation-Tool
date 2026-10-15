package com.jiezhimod.client.notice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 客户端通知仓库（右下角叠加 + 全屏历史列表共用）。
 *
 * 上限 300 条：再往 older 的会被挤掉，避免长时间挂机无限增长。
 * 注意所有访问都加锁 —— 网络包回调（client thread via addScheduledTask）
 * 与渲染线程是同一条
 */
public class ClientNoticeManager {

    /** 历史保留条数上限（需求：最多显示 300 条） */
    public static final int MAX_COUNT = 300;
    /** 右下角同屏最多叠几条 */
    public static final int MAX_ON_SCREEN = 6;

    private static final List<NoticeEntry> HISTORY = new ArrayList<NoticeEntry>();
    private static long nextId = 1L;

    private ClientNoticeManager() {
    }

    public static synchronized void add(String title, String body) {
        HISTORY.add(new NoticeEntry(nextId++, title, body, System.currentTimeMillis()));
        trimToMax(HISTORY, MAX_COUNT);
    }

    /** 只保留最新的 max 条（纯函数，便于自测） */
    static void trimToMax(List<NoticeEntry> list, int max) {
        if (list == null || max < 0) {
            return;
        }
        while (list.size() > max) {
            list.remove(0);
        }
    }

    /** 返回快照，避免遍历时被并发修改 */
    public static synchronized List<NoticeEntry> historyNewestFirst() {
        List<NoticeEntry> out = new ArrayList<NoticeEntry>(HISTORY);
        Collections.reverse(out);
        return out;
    }

    public static synchronized int size() {
        return HISTORY.size();
    }

    public static synchronized void clear() {
        HISTORY.clear();
        nextId = 1L;
    }

    /** 当前仍应叠在屏幕右下角的通知（新 -> 旧） */
    public static synchronized List<NoticeEntry> onScreen(long nowMs) {
        return NoticeEntry.visible(HISTORY, nowMs, MAX_ON_SCREEN);
    }
}
