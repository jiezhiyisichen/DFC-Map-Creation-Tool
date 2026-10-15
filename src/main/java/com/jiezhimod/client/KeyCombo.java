package com.jiezhimod.client;

/**
 * 通知历史快捷键的触发判定（纯函数，无 Minecraft 依赖，方便离线自测）。
 *
 * ## 为什么是"裸键"而不是组合键
 *
 * 1.12.2 的 KeyBinding 只能绑单键，玩家在设置里看到的就是一个键位。
 * 直接绑一个字母键会有两个害处：
 *
 * 1) **撞键**：这个字母键很可能已经被别的模组占用（多人整合包里尤其常见），
 *    表现为两个功能同时触发，或者谁都不触发。
 * 2) **打字劫持**：即使加了 Ctrl / Shift 组合键，别的模组照样可能占用同一组合键
 *    （Ctrl+P 是非常热门的组合键）；更糟的是某些模组在聊天输入时也会响应组合键。
 *
 * 所以本模组改用**一个游戏内基本没人用的功能键**（默认 P）作为**裸键**触发：
 * 撞键概率极低，也不存在"组合键被拦截"的问题。
 *
 * 唯一必须守住的底线是：**任何界面打开时都不触发**。
 * 1.12.2 里即使 GuiChat 正开着，KeyBinding.onTick 照样每 tick 累积按键状态，
 * 所以玩家在聊天框里打出字母 p 时，通知界面会立刻 displayGuiScreen 把聊天顶掉，
 * 输入到一半的文字和历史消息全部消失。只要判掉 currentScreen != null 就彻底安全。
 *
 * 玩家仍可在「设置 → 控制 → jiezhimod」里自由改成任意键，逻辑不变。
 */
public final class KeyCombo {

    private KeyCombo() {
    }

    /**
     * 是否应该打开通知历史界面。
     *
     * @param pressed    KeyBinding 本 tick 是否被按下（务必先消费掉 pressTime）
     * @param screenOpen 当前是否有界面打开（聊天、背包、设置……）
     */
    public static boolean shouldOpenNotice(boolean pressed, boolean screenOpen) {
        if (!pressed) {
            return false;
        }
        // 任何界面打开时都不触发：这是修"打字把聊天顶掉"的关键，也是裸键方案唯一的硬约束
        return !screenOpen;
    }

    /** 快捷键说明文本（给 lang / 提示用），玩家改了主键后这句也依然成立 */
    public static String describe(String mainKeyName) {
        if (mainKeyName == null || mainKeyName.isEmpty()) {
            return "(unbound)";
        }
        return mainKeyName;
    }
}
