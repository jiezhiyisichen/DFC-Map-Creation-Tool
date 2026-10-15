package com.jiezhimod.block;

import java.util.ArrayList;
import java.util.List;

/**
 * 延时队列：把"输入电平变化"按时间点排队，到点才真正翻转输出。
 *
 * 这样短脉冲也不会被吞掉——输入 ON 在 t=10、OFF 在 t=15，输出就会在
 * 10 tick 时变 ON、15 tick 时变 OFF，整段波形整体后移而不是被抹平。
 *
 * 纯逻辑（不碰 World），可离线自测。
 */
public final class DelayQueue {

    /** 队列上限，防止高频时钟把内存撑爆；超限丢最早的 */
    private static final int LIMIT = 64;

    private static final class Entry {
        final long time;
        final boolean state;

        Entry(long time, boolean state) {
            this.time = time;
            this.state = state;
        }
    }

    private final List<Entry> queue = new ArrayList<Entry>(8);
    private boolean state = false;

    /** 当前输出电平 */
    public boolean state() {
        return state;
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** 下一次翻转的世界时间；队列空时返回 Long.MAX_VALUE */
    public long nextFireTime() {
        return queue.isEmpty() ? Long.MAX_VALUE : queue.get(0).time;
    }

    /** 排一次电平变化：time 到达时把输出置成 newState */
    public void schedule(long time, boolean newState) {
        if (queue.size() >= LIMIT) {
            queue.remove(0);
        }
        queue.add(new Entry(time, newState));
    }

    /**
     * 处理所有到点（time <= now）的排队事件。
     *
     * @return 输出电平是否真的发生变化（没变就不用通知邻居）
     */
    public boolean applyDue(long now) {
        boolean changed = false;
        while (!queue.isEmpty() && queue.get(0).time <= now) {
            Entry e = queue.remove(0);
            if (e.state != state) {
                state = e.state;
                changed = true;
            }
        }
        return changed;
    }

    /** 清空排队（不改当前电平） */
    public void clear() {
        queue.clear();
    }

    /** 强制复位：清空排队并把电平置为给定值 */
    public void reset(boolean newState) {
        queue.clear();
        state = newState;
    }
}
