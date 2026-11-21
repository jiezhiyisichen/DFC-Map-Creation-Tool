package com.jiezhimod.client;

import com.jiezhimod.JiezhiMod;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/**
 * 进入世界时在<b>原版聊天栏</b>里打一条加载提示（v5.2.1）。
 *
 * <p>输出形如：</p>
 * <pre>
 *   jiezhimod 版本：5.2.1，已经加载，若遇到问题请私信节枝
 * </pre>
 * <p>（{@code jiezhimod} 青色、版本号黄色、其余默认白）</p>
 *
 * <h3>★★ 为什么分两个事件，而不是直接在连接事件里发</h3>
 * {@link FMLNetworkEvent.ClientConnectedToServerEvent} 触发时，网络通道刚建立，
 * <b>{@code Minecraft.player} 还是 null</b>（玩家实体要等 {@code SPacketJoinGame} + 区块下发之后才建）。
 * 那一刻发消息只能拿到 NPE，或者被迫用 {@code ingameGUI} 硬塞 —— 而那时聊天栏本身还没初始化好。
 *
 * <p>所以拆成两步：连接事件只<b>上膛</b>（{@code armed = true}），
 * 客户端 tick 里等 {@code player != null} 再<b>开火</b>并发完立刻卸膛。
 * 这样无论单人世界加载多慢、多人服务器延迟多大，都只会发一次。</p>
 *
 * <h3>★ 为什么不会刷屏 / 不会重复</h3>
 * <ul>
 *   <li>{@code armed} 发完立刻置回 {@code false}，每 tick 只判断不发；</li>
 *   <li>换维度（下界/末地）不会触发 {@code ClientConnectedToServerEvent} ⇒ 不会重发；</li>
 *   <li>只有<b>重新连接世界</b>才再次上膛 —— 那正是"入游提示"该出现的时候。</li>
 * </ul>
 *
 * <h3>★ 只在客户端</h3>
 * 本类只由 {@code ClientProxy.init()} 注册（{@code ClientProxy} 本身就只在物理客户端加载），
 * 服务端连这个类都不会碰到。{@code EntityPlayerSP#sendMessage} 是<b>纯本地</b>显示，
 * 不会往服务器发任何包，多人服里别人看不到这条消息。
 */
public final class WelcomeNotice {

    /** 版本号之后的固定后缀 */
    private static final String TAIL = "，已经加载，若遇到问题请私信节枝";

    /** 已上膛：连接建立、等着玩家实体出现 */
    private boolean armed;

    @SubscribeEvent
    public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        this.armed = true;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !this.armed) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || mc.world == null) {
            return;                      // 玩家实体/世界还没建好，下一个 tick 再看
        }
        this.armed = false;              // ★ 先卸膛：即使下面抛异常也不会每 tick 重发
        mc.player.sendMessage(build());
    }

    /** 拼这条消息。全程原版 {@link TextComponentString} + {@link TextFormatting}，不引任何第三方 */
    private static TextComponentString build() {
        TextComponentString root = new TextComponentString("");

        TextComponentString name = new TextComponentString("jiezhimod");
        name.setStyle(new Style().setColor(TextFormatting.AQUA));
        root.appendSibling(name);

        TextComponentString ver = new TextComponentString("版本：" + JiezhiMod.VERSION);
        ver.setStyle(new Style().setColor(TextFormatting.YELLOW));
        root.appendSibling(ver);

        // 后缀用默认白色（不加 style）：聊天栏在深色背景上，GRAY 会偏暗看不清
        root.appendSibling(new TextComponentString(TAIL));
        return root;
    }
}
