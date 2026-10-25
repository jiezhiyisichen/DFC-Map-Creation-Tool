package com.jiezhimod.client.dfc;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 每帧给显示器画布"报个到"。
 *
 * 【为什么需要这个类】{@link ScreenCanvas} 的重绘预算是"每帧最多画两块"，
 * 但 TESR 每一帧会被十几台屏各调一次，它自己分不清"这一帧"和"下一帧"。
 * {@link RenderWorldLastEvent} 在整帧世界渲染结束、下一帧开始之前触发一次，
 * 正好当帧边界用。
 *
 * 【为什么不挂在 RenderGameOverlayEvent】那个事件在 GUI 打开时也会派发，
 * 而 TESR 只在世界渲染时跑；用它会出现在"暂停菜单里也在重置预算"的错位。
 *
 * 【为什么放在 client.dfc 包】{@code proguard.pro} 里整包保留了
 * {@code com.jiezhimod.client.**} —— 事件方法一旦被改名，这个钩子会静默失效，
 * 表现就是"重绘预算永远不重置"，屏幕会变成几秒才动一下。
 */
@SideOnly(Side.CLIENT)
public class ScreenFrameHook {

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        ScreenCanvas.beginFrame();
    }
}
