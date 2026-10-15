package com.jiezhimod.command;

import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageNotice;
import com.jiezhimod.util.ColorUtil;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.util.Collections;
import java.util.List;

/**
 * /notice <标题> <正文...>
 *
 * 向所有在线玩家右下角推送一条通知。
 * 标题取第一个参数，其余全部拼成正文（所以正文里可以直接带空格，不需要引号）。
 * 支持颜色码：因为 1.12.2 输入框打不出 §，写字面 \u00a7 即可（与 /sub 一致），
 * 服务端收到后由 ColorUtil 解码成真正的分节号。
 */
public class CommandNotice extends CommandBase {

    @Override
    public String getName() {
        return "notice";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/notice <title> <body...>";
    }

    /** 与其它指令一致，默认需要 OP/命令方块（2 级） */
    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(getUsage(sender));
        }
        String title = ColorUtil.decode(args[0]);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        String body = ColorUtil.decode(sb.toString());

        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        for (EntityPlayerMP player : players) {
            ModNetwork.CHANNEL.sendTo(new MessageNotice(title, body), player);
        }
        sender.sendMessage(new TextComponentString(
                "Notice sent to " + players.size() + " player(s): " + ColorUtil.stripCodes(title)));
    }
}
