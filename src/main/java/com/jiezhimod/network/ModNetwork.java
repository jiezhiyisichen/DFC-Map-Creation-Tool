package com.jiezhimod.network;

import com.jiezhimod.JiezhiMod;
import com.jiezhimod.network.msg.MessageCameraShake;
import com.jiezhimod.network.msg.MessageCountdownSync;
import com.jiezhimod.network.msg.MessageCustomScreenAction;
import com.jiezhimod.network.msg.MessageDfcScreen;
import com.jiezhimod.network.msg.MessageJetExhaust;
import com.jiezhimod.network.msg.MessageNotice;
import com.jiezhimod.network.msg.MessageSubtitle;
import com.jiezhimod.network.msg.MessageSubtitleClear;
import com.jiezhimod.network.msg.MessageWhiteScreen;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class ModNetwork {
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(JiezhiMod.MODID);

    public static void init() {
        int id = 0;
        CHANNEL.registerMessage(MessageCameraShake.Handler.class, MessageCameraShake.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(MessageWhiteScreen.Handler.class, MessageWhiteScreen.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(MessageSubtitle.Handler.class, MessageSubtitle.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(MessageSubtitleClear.Handler.class, MessageSubtitleClear.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(MessageCountdownSync.Handler.class, MessageCountdownSync.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(MessageNotice.Handler.class, MessageNotice.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(MessageJetExhaust.Handler.class, MessageJetExhaust.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(MessageDfcScreen.Handler.class, MessageDfcScreen.class, id++, Side.CLIENT);
        // 自定义显示屏 GUI 的操作包（开机 / 删除某条 / 清空），客户端 -> 服务端
        CHANNEL.registerMessage(MessageCustomScreenAction.Handler.class,
                MessageCustomScreenAction.class, id++, Side.SERVER);
        // 密码控制台的设置包（红石动作 / 是否输出），客户端 -> 服务端
        CHANNEL.registerMessage(com.jiezhimod.network.msg.MessageConsoleConfig.Handler.class,
                com.jiezhimod.network.msg.MessageConsoleConfig.class, id++, Side.SERVER);
        // 调试界面的设置包（目前只有屏幕刷新率），客户端 -> 服务端
        CHANNEL.registerMessage(com.jiezhimod.network.msg.MessageDebugConfig.Handler.class,
                com.jiezhimod.network.msg.MessageDebugConfig.class, id++, Side.SERVER);
    }
}
