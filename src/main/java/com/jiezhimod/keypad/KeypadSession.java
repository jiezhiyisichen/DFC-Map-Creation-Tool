package com.jiezhimod.keypad;

import java.util.Random;

/**
 * 「这一次进游戏」的会话令牌。
 *
 * 【解决什么问题】需求："随机密码**进游戏时自动刷新**"。
 * 每台密码盘的随机密码都存在自己的 NBT 里，所以只要不删方块，密码就一直不变 ——
 * 玩家关掉游戏明天再来，便签上还是那 6 位数字，等于密码从来没换过。
 *
 * 【怎么判断"进游戏"】用一个**每次会话都不同**的随机整数当令牌：
 *   · 进程启动时随机构造一个（{@link #token}）；
 *   · 每次服务端启动（进存档 / 退回主菜单再进 / 开服）再换一个。
 * {@link com.jiezhimod.block.TileKeypad} 把自己上次见到的令牌写进 NBT，
 * 第一次 tick 时对不上就重新随机密码，并立刻记下新令牌。
 *
 * 【为什么不用"世界加载次数"或时间戳】那些要么会随区块重载变化（走远再回来密码就变了，
 * 玩家会以为坏了），要么写进 NBT 后下次启动刚好相等而漏刷。随机整数 32 位，
 * 两次会话撞车的概率是 1/2^32，可以当不会发生。
 */
public final class KeypadSession {

    /** 当前会话令牌（每次启动 / 每次进存档都换） */
    private static int token = new Random().nextInt();

    private KeypadSession() {
    }

    public static int token() {
        return token;
    }

    /** 开一局新的（服务端启动时调用） */
    public static void newSession() {
        token = new Random().nextInt();
    }
}
