package com.jiezhimod.block;

import com.jiezhimod.keypad.KeypadLayout;
import com.jiezhimod.keypad.KeypadSession;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;

import java.util.Random;

/**
 * 密码盘的方块实体：**一台一密码**，随机生成，写在便签上给玩家看。
 *
 * 【服务端权威】按键判定、比对、锁定全部在服务端做；
 * 客户端只负责把面板画出来（显示已输入的位数、锁定状态、输错的红色反馈）。
 * 任何状态变化都走 {@link #sync()} 推给客户端，客户端不做本地预测 ——
 * 否则会出现"客户端显示输对了、服务端判定错"的错位。
 *
 * 【密码什么时候生成】{@code TileEntity} 构造时 {@code world} 还是 null，
 * 没法拿随机源，所以放到第一次 {@link #update()} 里生成，并立刻写 NBT。
 */
public class TileKeypad extends TileEntity implements ITickable {

    /** 密码位数（截图里是 6 位） */
    public static final int PASSWORD_LEN = 6;

    /** 便签纸上那行字最多几个字符（需求） */
    public static final int NOTE_MAX = 8;

    /** 密码模式：进游戏时随机刷新 */
    public static final int MODE_RANDOM = 0;
    /** 密码模式：玩家在调试界面里自己定的固定密码 */
    public static final int MODE_FIXED = 1;

    /** 两次按键之间的最小间隔（tick）。防止连点/长按把一串数字刷进去 */
    private static final int PRESS_COOLDOWN = 3;
    /** 输错后的红色反馈持续 tick */
    private static final int REJECT_TICKS = 12;

    private String password = "";
    private String buffer = "";
    /** 密码模式，取 {@link #MODE_RANDOM} / {@link #MODE_FIXED} */
    private int passwordMode = MODE_RANDOM;
    /** 固定密码（{@link #MODE_FIXED} 时才有效；必须是 PASSWORD_LEN 位纯数字） */
    private String fixedPassword = "";
    /** 便签纸上的自定义文字（空 = 直接显示当前密码） */
    private String noteText = "";
    /**
     * 上一次"跟着哪个存档会话跑"的令牌。
     *
     * 【为什么要它】需求："随机密码进游戏时自动刷新"。
     * 每次进游戏（新建/读档/开服）{@code KeypadSession} 会换一个新令牌，
     * 方块实体第一次 tick 时发现对不上就重新随机一个密码；令牌会写进 NBT，
     * 所以**走远再回来、区块重载**都不会把密码莫名换掉。
     */
    private int sessionToken;
    private boolean locked;
    /** 已经通过验证（控制台据此输出红石） */
    private boolean accepted;

    private int cooldown;
    private int rejectTicks;

    // ==================== 状态 ====================

    public String getPassword() {
        return password;
    }

    /** 当前**真正生效**的密码：固定模式用固定密码，否则用随机密码 */
    public String getActivePassword() {
        if (passwordMode == MODE_FIXED && isValidPassword(fixedPassword)) {
            return fixedPassword;
        }
        return password;
    }

    public int getPasswordMode() {
        return passwordMode;
    }

    public String getFixedPassword() {
        return fixedPassword;
    }

    /** 便签纸上要画的东西：有自定义文字就画文字，否则画当前密码 */
    public String noteDisplay() {
        return noteText == null || noteText.isEmpty() ? getActivePassword() : noteText;
    }

    public String getNoteText() {
        return noteText == null ? "" : noteText;
    }

    /** 6 位纯数字才算合法密码 */
    public static boolean isValidPassword(String s) {
        if (s == null || s.length() != PASSWORD_LEN) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 调试界面下发的配置（服务端权威）。
     *
     * 【校验规则】非法值一律**降级成安全的那一边**，绝不"存进去但不生效"：
     *   · mode 只认 {@link #MODE_FIXED} 且密码必须在 {@link #PASSWORD_LEN} 位纯数字，
     *     否则退回 {@link #MODE_RANDOM}（玩家不会因为填错而把自己锁在外面）；
     *   · 便签文字裁到 {@link #NOTE_MAX} 个字符，去掉首尾空白。
     */
    public void applyConfig(int mode, String fixed, String note) {
        String f = fixed == null ? "" : fixed.trim();
        String n = note == null ? "" : note.trim();
        if (n.length() > NOTE_MAX) {
            n = n.substring(0, NOTE_MAX);
        }
        boolean wantFixed = mode == MODE_FIXED && isValidPassword(f);
        this.passwordMode = wantFixed ? MODE_FIXED : MODE_RANDOM;
        this.fixedPassword = wantFixed ? f : "";
        this.noteText = n;
        // 换成固定密码时，把已输入的一半清掉，免得下一次「输入」按旧预期走
        this.buffer = "";
        this.accepted = false;
        markDirty();
        sync();
    }

    public String getBuffer() {
        return buffer;
    }

    public boolean isLocked() {
        return locked;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public boolean isRejecting() {
        return rejectTicks > 0;
    }

    /** 还剩几位没输 */
    public int remaining() {
        return Math.max(0, PASSWORD_LEN - buffer.length());
    }

    public void setLocked(boolean locked) {
        if (this.locked == locked) {
            return;
        }
        this.locked = locked;
        if (locked) {
            this.buffer = "";
        }
        markDirty();
        sync();
    }

    /** 控制台"清空输入"用：只清输入缓冲，不影响是否已通过 */
    public void clearBuffer() {
        if (this.buffer.isEmpty()) {
            return;
        }
        this.buffer = "";
        markDirty();
        sync();
    }

    /** 控制台"重置"用：把验证结果也撤掉，输出随之关闭 */
    public void resetAccepted() {
        if (!this.accepted) {
            return;
        }
        this.accepted = false;
        markDirty();
        sync();
    }

    // ==================== 按键 ====================

    /**
     * 按下一个键。
     *
     * @return true 表示这次按键真的被处理了（调用方据此放声音 / 出提示）
     */
    public boolean press(int key) {
        if (this.world == null || this.world.isRemote) {
            return false;
        }
        if (this.cooldown > 0 || this.locked) {
            return false;
        }
        this.cooldown = PRESS_COOLDOWN;

        boolean changed = true;
        int digit = KeypadLayout.digit(key);
        if (digit >= 0) {
            if (this.buffer.length() < PASSWORD_LEN) {
                this.buffer = this.buffer + (char) ('0' + digit);
            } else {
                // 已经输满 6 位：再按数字不再追加，等玩家按「输入」或「清除」
                changed = false;
            }
        } else if (key == KeypadLayout.KEY_CLEAR) {
            this.buffer = "";
        } else if (key == KeypadLayout.KEY_ENTER) {
            changed = submit();
        } else {
            changed = false;
        }

        if (changed) {
            markDirty();
            sync();
        }
        return changed;
    }

    private boolean submit() {
        if (this.buffer.length() != PASSWORD_LEN) {
            reject();
            return false;
        }
        if (this.buffer.equals(getActivePassword())) {
            this.accepted = true;
            this.buffer = "";
            return true;
        }
        reject();
        return false;
    }

    private void reject() {
        this.buffer = "";
        this.rejectTicks = REJECT_TICKS;
    }

    // ==================== tick ====================

    @Override
    public void update() {
        if (this.world == null) {
            return;
        }
        // 【每次进游戏刷新随机密码】见 sessionToken 的说明。
        // 只在服务端做 —— 客户端跟着收包就行，否则两边各随机一个必然对不上。
        if (!this.world.isRemote && this.sessionToken != KeypadSession.token()) {
            this.sessionToken = KeypadSession.token();
            if (this.passwordMode == MODE_RANDOM) {
                this.password = randomPassword(this.world.rand);
            }
            markDirty();
            sync();
        }
        // 兜底：固定模式下调进来时密码可能还是空的
        if (this.password.isEmpty()) {
            this.password = randomPassword(this.world.rand);
            markDirty();
            sync();
        }
        if (this.cooldown > 0) {
            this.cooldown--;
        }
        if (this.rejectTicks > 0) {
            this.rejectTicks--;
            if (this.rejectTicks == 0) {
                // 红色反馈结束，推一次让客户端把边框恢复正常色
                sync();
            }
        }
    }

    /** 生成一串 {@link #PASSWORD_LEN} 位纯数字密码 */
    public static String randomPassword(Random rand) {
        StringBuilder sb = new StringBuilder(PASSWORD_LEN);
        for (int i = 0; i < PASSWORD_LEN; i++) {
            sb.append((char) ('0' + rand.nextInt(10)));
        }
        return sb.toString();
    }

    // ==================== 同步 / 存盘 ====================

    public void sync() {
        if (this.world != null && !this.world.isRemote) {
            this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos),
                    this.world.getBlockState(this.pos), 3);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        super.writeToNBT(c);
        c.setString("Password", this.password);
        c.setInteger("Mode", this.passwordMode);
        c.setString("Fixed", this.fixedPassword);
        c.setString("Note", this.noteText);
        c.setInteger("Session", this.sessionToken);
        c.setString("Buffer", this.buffer);
        c.setBoolean("Locked", this.locked);
        c.setBoolean("Accepted", this.accepted);
        c.setInteger("Reject", this.rejectTicks);
        return c;
    }

    @Override
    public void readFromNBT(NBTTagCompound c) {
        super.readFromNBT(c);
        this.password = c.getString("Password");
        this.passwordMode = c.getInteger("Mode");
        this.fixedPassword = c.getString("Fixed");
        this.noteText = c.getString("Note");
        this.sessionToken = c.getInteger("Session");
        this.buffer = c.getString("Buffer");
        this.locked = c.getBoolean("Locked");
        this.accepted = c.getBoolean("Accepted");
        this.rejectTicks = c.getInteger("Reject");
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }

    /** 区块卸载后客户端要重建画面，所以这里也要求完整同步 */
    @Override
    public boolean shouldRefresh(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos,
                                 net.minecraft.block.state.IBlockState oldState,
                                 net.minecraft.block.state.IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }

    // ==================== 客户端渲染缓存 ====================

    /**
     * 面板画布。存成 Object 的理由同 {@code TileDfcScreen}：
     * 字段类型若是客户端类，服务端反序列化这个 TE 时会多一次类解析，
     * 而服务端根本用不到它 —— 只有 TESR 会调 {@link #canvas()}。
     */
    private Object canvasHolder;

    @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
    public com.jiezhimod.client.dfc.ScreenCanvas canvas() {
        if (this.canvasHolder == null) {
            com.jiezhimod.client.dfc.ScreenCanvas c = new com.jiezhimod.client.dfc.ScreenCanvas();
            // 每台密码盘一张自己的纹理（坐标 + identityHashCode 双保险，
            // 避免方块快速拆装时出现重复的 ResourceLocation）
            c.setLocation(new net.minecraft.util.ResourceLocation("jiezhimod",
                    "keypad_" + this.pos.getX() + "_" + this.pos.getY() + "_" + this.pos.getZ()
                            + "_" + Integer.toHexString(System.identityHashCode(this))));
            this.canvasHolder = c;
        }
        return (com.jiezhimod.client.dfc.ScreenCanvas) this.canvasHolder;
    }

    /** 拆掉/换块时释放 GL 纹理，否则每摆一台就漏一张 768×576 的显存贴图 */
    @Override
    public void invalidate() {
        super.invalidate();
        if (this.world != null && this.world.isRemote
                && this.canvasHolder instanceof com.jiezhimod.client.dfc.ScreenCanvas) {
            ((com.jiezhimod.client.dfc.ScreenCanvas) this.canvasHolder).dispose();
            this.canvasHolder = null;
        }
    }
}
