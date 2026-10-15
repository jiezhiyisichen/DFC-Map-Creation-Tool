package com.jiezhimod.client.notice;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条通知的数据。
 *
 * 纯数据类：不引 Minecraft，便于离线自测。
 * title / body 里允许出现 § 颜色码（玩家在指令里写字面 \u00a7，由服务端指令转成真正分节号）。
 */
public class NoticeEntry {

    public final long id;
    public final String title;
    public final String body;
    /** 诞生时刻（毫秒，真实时间） */
    public final long bornMs;

    public NoticeEntry(long id, String title, String body, long bornMs) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
        this.bornMs = bornMs;
    }

    /** 通知没有标题时用它兜底，避免出现完全空白的一行 */
    public String displayTitle() {
        return title.isEmpty() ? "Notice" : title;
    }

    /**
     * 入场 / 保持 / 淡出的生命周期与聊天栏一致：
     * 8 秒后开始向左滑出并淡出。
     */
    public static final long ENTER_MS = 280L;
    public static final long HOLD_MS = 8000L;
    public static final long FADE_MS = 900L;

    /** 超过阈值（含淡出）就彻底过期 */
    public static boolean isGone(long ageMs) {
        return ageMs >= HOLD_MS + FADE_MS;
    }

    /**
     * 从旧到新的列表里，找出当前还应该在屏幕上显示的那些（返回顺序：新 -> 旧）。
     * @param maxCount 最多同屏显示几条
     */
    public static List<NoticeEntry> visible(List<NoticeEntry> history, long nowMs, int maxCount) {
        List<NoticeEntry> out = new ArrayList<NoticeEntry>();
        if (history == null || maxCount <= 0) {
            return out;
        }
        for (int i = history.size() - 1; i >= 0 && out.size() < maxCount; i--) {
            NoticeEntry e = history.get(i);
            if (!isGone(nowMs - e.bornMs)) {
                out.add(e);
            }
        }
        return out;
    }
}
