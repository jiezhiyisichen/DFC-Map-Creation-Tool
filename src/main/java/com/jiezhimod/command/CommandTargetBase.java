package com.jiezhimod.command;

import com.jiezhimod.countdown.ServerCountdownManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * 模组指令基类。
 *
 * 统一提供：
 *  1. 目标选择器解析：支持原版全部选择器 @p / @a / @r / @e / @s 及其参数形式
 *     （如 @a[team=red]、@p[c=3]、@e[type=player]），由原版 EntitySelector 完成解析，
 *     与 /kill、/effect 等原版指令行为完全一致；
 *  2. 权限等级 2：命令方块、命令方块矿车、OP 玩家均可直接执行，
 *     修复命令方块调用时报"无权使用该指令"的问题；
 *  3. 选择器 Tab 补全。
 */
public abstract class CommandTargetBase extends CommandBase {

    /**
     * 权限等级设为 2：
     * 命令方块 / 命令方块矿车（权限等级 2）可以执行，修复"无权使用该指令"的 bug；
     * 同时 2 &gt;= 1，满足 EntitySelector 解析选择器所要求的执行者权限（>=1），
     * 因此 @p/@a/@r/@e/@s 在命令方块和 OP 玩家手中都能正常工作。
     * 如需放行普通玩家（无 OP），可改为 0（但普通玩家仍受原版限制无法解析选择器）；如需仅管理员可用，可改为 4。
     */
    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    /**
     * 解析目标玩家列表：
     *  1. 首参数以 "@" 开头 -> 按选择器解析（@p/@a/@r/@e/@s 及其参数）；
     *  2. 否则执行者为玩家 -> 仅作用于该玩家；
     *  3. 否则（控制台 / 命令方块等非玩家执行者）-> 作用于所有在线玩家。
     */
    protected List<EntityPlayerMP> resolveTargets(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length > 0 && args[0].startsWith("@")) {
            // EntitySelector.getPlayers 支持 @p/@a/@r/@e/@s 及其参数形式，空列表时不会抛异常
            List<EntityPlayerMP> players = EntitySelector.getPlayers(sender, args[0]);
            if (players.isEmpty()) {
                throw new CommandException("No targets matched: %s", args[0]);
            }
            return players;
        }

        if (sender instanceof EntityPlayerMP) {
            return Collections.singletonList((EntityPlayerMP) sender);
        }

        return server.getPlayerList().getPlayers();
    }

    /** 首个参数支持 @p @a @r @e @s 的 Tab 补全 */
    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "@p", "@a", "@r", "@e", "@s");
        }
        return Collections.emptyList();
    }

    /** 首个参数补全已存在的倒计时名称（删除/暂停/继续/检测指令用） */
    protected List<String> completeCountdownNames(String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, ServerCountdownManager.names());
        }
        return Collections.emptyList();
    }
}
