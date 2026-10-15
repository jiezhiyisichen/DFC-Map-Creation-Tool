package com.jiezhimod.command;

import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageSubtitleClear;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.util.Arrays;
import java.util.List;

public class CommandSubtitleClear extends CommandTargetBase {
    @Override
    public String getName() {
        return "subtitleclear";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("clearsubtitle", "subclear");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/subtitleclear [@target]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        List<EntityPlayerMP> targets = resolveTargets(server, sender, args);

        for (EntityPlayerMP player : targets) {
            ModNetwork.CHANNEL.sendTo(new MessageSubtitleClear(), player);
        }

        String message;
        if (targets.size() == 1 && sender instanceof EntityPlayerMP && targets.get(0).getUniqueID().equals(((EntityPlayerMP) sender).getUniqueID())) {
            message = "Cleared your subtitle queue";
        } else if (targets.size() == server.getPlayerList().getPlayers().size()) {
            message = "Cleared subtitle queues of all online players";
        } else {
            message = "Cleared subtitle queues of " + targets.size() + " targets";
        }
        sender.sendMessage(new TextComponentString(message));
    }
}
