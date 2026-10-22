package com.jiezhimod.network.msg;

import com.jiezhimod.block.TileKeypad;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;

/**
 * 客户端 → 服务端：调试界面里改**密码盘**的配置（密码模式 / 固定密码 / 便签文字）。
 *
 * 【为什么把三样东西塞进一个包】它们在界面上是同一张表单、一起按「保存」提交。
 * 分三个包会出现"模式改了、密码还没到"的中间态 —— 那一瞬间如果玩家按了「输入」，
 * 判定用的还是旧密码。一个包 = 服务端原子地应用整套配置。
 *
 * 【永远不要相信客户端】字符串长度、密码格式、模式取值全部在服务端再校验一遍，
 * 非法的部分降级成安全值（见 {@link TileKeypad#applyConfig}），不抛异常、不写坏存档。
 */
public class MessageKeypadConfig implements IMessage {

    /** 便签文字上限（与 {@link TileKeypad#NOTE_MAX} 一致，这里再挡一次） */
    private static final int NOTE_LIMIT = 32;

    private BlockPos pos = BlockPos.ORIGIN;
    private int mode;
    private String fixed = "";
    private String note = "";

    public MessageKeypadConfig() {
    }

    public MessageKeypadConfig(BlockPos pos, int mode, String fixed, String note) {
        this.pos = pos;
        this.mode = mode;
        this.fixed = fixed == null ? "" : fixed;
        this.note = note == null ? "" : note;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        this.mode = buf.readByte();
        this.fixed = readStr(buf);
        this.note = readStr(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.pos.getX());
        buf.writeInt(this.pos.getY());
        buf.writeInt(this.pos.getZ());
        buf.writeByte(this.mode);
        writeStr(buf, this.fixed);
        writeStr(buf, this.note);
    }

    /** 1 字节长度 + UTF-8（最长 255 字节，够 6 位密码和 8 个汉字） */
    private static void writeStr(ByteBuf buf, String s) {
        byte[] b = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(b.length, 255);
        buf.writeByte(n);
        buf.writeBytes(b, 0, n);
    }

    private static String readStr(ByteBuf buf) {
        int n = buf.readByte() & 255;
        if (n == 0) {
            return "";
        }
        byte[] b = new byte[n];
        buf.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public BlockPos pos() {
        return pos;
    }

    public int mode() {
        return mode;
    }

    public String fixed() {
        return fixed;
    }

    public String note() {
        return note;
    }

    public static class Handler implements IMessageHandler<MessageKeypadConfig, IMessage> {
        @Override
        public IMessage onMessage(MessageKeypadConfig msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                // 8 格以内才允许调（和调试界面改显示器刷新率同款判定）
                if (player.getDistanceSq(msg.pos.getX() + 0.5D, msg.pos.getY() + 0.5D,
                        msg.pos.getZ() + 0.5D) > 64.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(msg.pos);
                if (!(te instanceof TileKeypad)) {
                    return;
                }
                String fixed = msg.fixed.length() > 16 ? msg.fixed.substring(0, 16) : msg.fixed;
                String note = msg.note.length() > NOTE_LIMIT ? msg.note.substring(0, NOTE_LIMIT) : msg.note;

                TileKeypad pad = (TileKeypad) te;
                pad.applyConfig(msg.mode, fixed, note);

                // 服务端可能把"固定"降级成"随机"（密码不合法），如实告诉玩家，
                // 否则他会以为填进去的固定密码生效了，然后在门外输一整晚。
                if (msg.mode == TileKeypad.MODE_FIXED && pad.getPasswordMode() != TileKeypad.MODE_FIXED) {
                    player.sendStatusMessage(new TextComponentString(
                            TextFormatting.RED + "[jiezhimod] "
                                    + TextFormatting.RESET + "固定密码必须是 "
                                    + TileKeypad.PASSWORD_LEN + " 位纯数字，已保持随机模式"), false);
                }
            });
            return null;
        }
    }
}
