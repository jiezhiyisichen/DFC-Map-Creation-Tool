package com.jiezhimod.command;

import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageSubtitle;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.util.Arrays;
import java.util.List;

public class CommandSubtitle extends CommandTargetBase {
    @Override
    public String getName() {
        return "sub";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("subtitle");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/sub [@target] [ticks] <text...>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new CommandException("usage: %s", getUsage(sender));
        }

        List<EntityPlayerMP> targets = resolveTargets(server, sender, args);
        String[] params = args;
        if (args[0].startsWith("@")) {
            params = Arrays.copyOfRange(args, 1, args.length);
        }

        if (params.length < 1) {
            throw new CommandException("usage: %s", getUsage(sender));
        }

        int ticks;
        String text;
        if (params.length >= 2 && params[0].matches("-?\\d+")) {
            ticks = parseInt(params[0], 1, 2000);
            text = joinSubtitleText(params, 1);
        } else {
            ticks = 80;
            text = joinSubtitleText(params, 0);
        }
        // 将字面 "\u00a7" 转义为真正的分节号 §，便于在聊天框/命令方块中输入颜色码；
        // 直接输入 § 字符也会原样保留，渲染时由 FontRenderer 解析为颜色。
        text = text.replace("\\u00a7", "\u00a7");

        for (EntityPlayerMP player : targets) {
            ModNetwork.CHANNEL.sendTo(new MessageSubtitle(ticks, text), player);
        }

        String message;
        if (targets.size() == 1 && sender instanceof EntityPlayerMP && targets.get(0).getUniqueID().equals(((EntityPlayerMP) sender).getUniqueID())) {
            message = "Sent subtitle to yourself: " + text;
        } else if (targets.size() == server.getPlayerList().getPlayers().size()) {
            message = "Broadcast subtitle: " + text;
        } else {
            message = "Sent subtitle to " + targets.size() + " targets: " + text;
        }
        sender.sendMessage(new TextComponentString(message));
    }

    /**
     * 将参数列表合并为字幕文本。
     * 1.12.2 的指令系统按空格分割、不解析双引号，这里手动合并双引号包裹的文本并去掉引号，
     * 使 /sub @a 80 "hello world" 能正确显示为 hello world 而不是带引号。
     */
    private static String joinSubtitleText(String[] params, int fromIndex) {
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (int i = fromIndex; i < params.length; i++) {
            String p = params[i];
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (inQuote) {
                sb.append(p);
                if (p.endsWith("\"")) {
                    sb.deleteCharAt(sb.length() - 1);
                    inQuote = false;
                }
            } else if (p.startsWith("\"")) {
                if (p.length() > 1 && p.endsWith("\"")) {
                    // 单个参数即完整引号对："abc"
                    sb.append(p.substring(1, p.length() - 1));
                } else {
                    // 引号开启，可能跨多个参数："abc def
                    sb.append(p.substring(1));
                    inQuote = true;
                }
            } else {
                sb.append(p);
            }
        }
        return sb.toString();
    }
}
