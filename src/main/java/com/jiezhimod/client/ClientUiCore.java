package com.jiezhimod.client;

import com.jiezhimod.client.chat.JiezhiChatGui;
import com.jiezhimod.client.notice.GuiNoticeList;
import com.jiezhimod.client.notice.NoticeHud;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

/**
 * 客户端 UI 总装：接管聊天栏、右下角通知叠加、以及打开通知历史的快捷键。
 *
 * 按键设计：默认 **P 键裸键触发**（游戏内基本没人用这个键，撞键概率极低，
 * 也不存在"组合键被别的 mod 占用"的问题）。玩家可在
 * 「设置 → 控制 → jiezhimod」里自由改成任意键，逻辑不变。
 *
 * 唯一的硬约束：**任何界面打开时都不触发**。1.12.2 即使 GuiChat 开着，
 * KeyBinding.onTick 照样每 tick 累积按键状态，玩家在聊天框打出字母 p 时
 * 通知界面会把 GuiChat 顶掉，已输入文字与历史全丢。只要 currentScreen != null 就安全。
 */
@SideOnly(Side.CLIENT)
public class ClientUiCore {

    public static final net.minecraft.client.settings.KeyBinding KEY_NOTICE =
            new net.minecraft.client.settings.KeyBinding(
                    "key.jiezhimod.notice", KeyConflictContext.IN_GAME, Keyboard.KEY_P,
                    "key.categories.jiezhimod");

    private static boolean chatInstalled = false;
    private static int installAttempts = 0;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }

        // 快捷键：每 tick 问一次 isPressed()。
        // 【为什么不能挂在 InputEvent.KeyInputEvent 上】
        // 原版 Minecraft.runTickKeyboard() 在 while(Keyboard.next()) 循环内部、
        // 且**只在 Keyboard.getEventKeyState() 为真的分支里**调用 KeyBinding.onTick(keyCode)
        // 来累加 pressTime；而 Forge 的 fireKeyInput()（派发 KeyInputEvent）位置在
        // 该分支之外、每次原生键事件（含松键、含其它键）都会触发。
        // 两者的时序不保证一致，pressTime 可能还没被累加就去读 isPressed()，
        // 于是读到 false —— 表现为"按 P 完全没反应"。
        // 改成在 tick 末尾轮询，与原版处理 isPressed() 的节奏对齐，行为确定。
        pollNoticeKey(mc);

        if (chatInstalled || mc.ingameGUI == null) {
            return;
        }
        // 最多试 3 次；失败就保留原版聊天，不刷日志
        installAttempts++;
        chatInstalled = JiezhiChatGui.install(mc);
        if (!chatInstalled && installAttempts >= 3) {
            chatInstalled = true;
        }
    }

    /**
     * 轮询通知历史快捷键。
     *
     * isPressed() 有副作用（每次调用吃掉一格 pressTime），所以**必须每 tick 恰好问一次**：
     * 问少了会漏触发，问多了会把一格 pressTime 分摊到多个 tick、造成"要按好几下"。
     * 无论当前有没有界面打开都要问，避免在聊天框里打字时 pressTime 持续累积。
     */
    private void pollNoticeKey(Minecraft mc) {
        boolean pressed = KEY_NOTICE.isPressed();
        if (pressed && mc.currentScreen instanceof GuiNoticeList) {
            // 已经打开时再按一次 = 关闭（比"只能按 Esc 退出"顺手）
            mc.displayGuiScreen(null);
            return;
        }
        if (!KeyCombo.shouldOpenNotice(pressed, mc.currentScreen != null)) {
            return;
        }
        openNoticeList();
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        NoticeHud.draw(mc, event.getResolution());
    }

    /** 打开通知历史界面（也可被指令/其它入口调用） */
    public static void openNoticeList() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            mc.displayGuiScreen(new GuiNoticeList());
        }
    }
}
