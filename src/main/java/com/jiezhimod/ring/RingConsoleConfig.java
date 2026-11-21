package com.jiezhimod.ring;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;

/**
 * 灯圈控制台的一条**动作配置**（收到红石信号后干什么）。
 *
 * <h3>为什么动作是"多选"的位掩码</h3>
 * 需求原话："控制台可选是可以多选的，比如开启灯顺便切换 RGB"。
 * 所以不是单选下拉框，而是四个开关位，触发时**按固定顺序**执行：
 * 先变颜色（这样"开灯 + 换色"开出来的就是新颜色）→ 再决定亮 / 灭 → 最后闪烁。
 *
 * <h3>控制台怎么串成一条链</h3>
 * 控制台只在自己收到红石**上升沿**时打一发，改的是**当时**灯圈组的状态：
 * A 控制台淡入到红色，红石延迟 2 秒后触发 B 控制台再淡入到蓝色 ——
 * 两次之间互不知道对方存在，只通过"组状态"接力（{@link #apply} 的起点是
 * {@code 当前状态此刻的颜色}），所以任意个控制台随便串。
 *
 * <p>纯数据 + 纯函数：{@link #apply} 不碰世界，离线自测可以直接断言。</p>
 */
public class RingConsoleConfig {

    // ---- 动作位（多选） ----
    /** 切换颜色至指定 RGB */
    public static final int BIT_COLOR = 1;
    /** 开启灯圈 */
    public static final int BIT_ON = 2;
    /** 关闭灯圈 */
    public static final int BIT_OFF = 4;
    /** 闪烁（重复关开） */
    public static final int BIT_BLINK = 8;

    public static final int ACTION_COUNT = 4;

    // ---- 上限 ----
    /** 单次过渡 / 闪烁参数的最大 tick（100 秒，再长就该用多个控制台接力了） */
    public static final int MAX_TICKS = 2000;
    /** 闪烁周期最短 2 tick（1 tick 亮 1 tick 灭已经是极限了） */
    public static final int MIN_PERIOD = 2;

    /** 动作位掩码 */
    public int actions;
    /** 目标颜色 {@code 0xRRGGBB} */
    public int color = 0xFF3A3A;

    /** 颜色是否慢慢变换（false = 直接切换） */
    public boolean colorFade;
    /** 颜色过渡时长（tick） */
    public int colorFadeTicks = 20;

    /** 开灯是否淡入 */
    public boolean onFade;
    /** 开灯淡入时长（tick） */
    public int onFadeTicks = 10;

    /** 关灯是否淡出 */
    public boolean offFade;
    /** 关灯淡出时长（tick） */
    public int offFadeTicks = 10;

    /** 闪烁周期（tick） */
    public int blinkPeriod = 20;
    /** 闪烁总时长（tick），走完自动停 */
    public int blinkTotal = 100;
    /** 每次亮起的淡入（tick）；0 = 直接亮 */
    public int blinkFadeIn;
    /** 每次熄灭的淡出（tick）；0 = 直接灭 */
    public int blinkFadeOut;

    public RingConsoleConfig() {
    }

    /** 整份拷贝（GUI 编辑副本、网络回传、服务端落盘三处都用同一个口子） */
    public void copyFrom(RingConsoleConfig o) {
        if (o == null) {
            return;
        }
        this.actions = o.actions;
        this.color = o.color & 0xFFFFFF;
        this.colorFade = o.colorFade;
        this.colorFadeTicks = o.colorFadeTicks;
        this.onFade = o.onFade;
        this.onFadeTicks = o.onFadeTicks;
        this.offFade = o.offFade;
        this.offFadeTicks = o.offFadeTicks;
        this.blinkPeriod = o.blinkPeriod;
        this.blinkTotal = o.blinkTotal;
        this.blinkFadeIn = o.blinkFadeIn;
        this.blinkFadeOut = o.blinkFadeOut;
    }

    // ==================== 执行 ====================

    /**
     * 把这条配置打到一组灯圈的当前状态上，返回新状态。
     *
     * @param cur 触发那一刻灯圈组的状态（只读，不会被改）
     * @return 打完之后的新状态（永远返回新对象）
     */
    public RingState apply(RingState cur) {
        RingState s = cur == null ? new RingState() : cur.copy();

        // 1) 颜色：从"此刻的颜色"继续过渡 —— 这样 A 控制台淡入到红、
        //    B 控制台接着淡入到蓝时，是从红色出发，而不是跳回上上次的颜色。
        if ((this.actions & BIT_COLOR) != 0) {
            // 快照里的 elapsed 已经是"此刻走到哪了"，再传 0 偏移就是当前颜色
            s.colorFrom = s.colorTo(0);
            s.colorTo = this.color & 0xFFFFFF;
            s.colorFadeDur = this.colorFade ? clampTicks(this.colorFadeTicks, 1) : 0;
            s.colorFadeElapsed = 0;
        }

        // 2) 亮 / 灭（开和关同时勾选时以"关"为准 —— 少数玩家的手滑场景，选一个确定的语义）
        if ((this.actions & BIT_OFF) != 0) {
            s.on = false;
        } else if ((this.actions & BIT_ON) != 0) {
            s.on = true;
        }

        // 3) 闪烁：闪烁期间亮灭由周期算，稳态 on 只是"闪完之后回到哪"
        if ((this.actions & BIT_BLINK) != 0) {
            s.blink = true;
            s.blinkPeriod = clampTicks(this.blinkPeriod, MIN_PERIOD);
            // ★ 0 = 一直闪（由下一个控制台 / 红石叫停）。以前这里被夹到最小 1 刻，
            //   于是"闪烁"总时长只有 1 刻，玩家看到的就是"闪一下立刻停"。
            s.blinkTotal = this.blinkTotal <= 0 ? 0 : clampTicks(this.blinkTotal, 1);
            if (this.blinkFadeIn > 0 || this.blinkFadeOut > 0) {
                s.blinkFadeIn = clampTicks(this.blinkFadeIn, 0);
                s.blinkFadeOut = clampTicks(this.blinkFadeOut, 0);
            } else {
                s.blinkFadeIn = 0;
                s.blinkFadeOut = 0;
            }
            s.blinkElapsed = 0;
        } else {
            s.blink = false;
            s.blinkPeriod = 0;
            s.blinkTotal = 0;
            s.blinkFadeIn = 0;
            s.blinkFadeOut = 0;
        }
        return s;
    }

    /** 有没有勾任何动作（一个都没勾 = 触发了也不动，GUI 里给提示） */
    public boolean isEmpty() {
        return (this.actions & (BIT_COLOR | BIT_ON | BIT_OFF | BIT_BLINK)) == 0;
    }

    /** 人话摘要（GUI 底部提示 / 日志）：例如「换色 #00FFFF · 开灯 · 闪烁」 */
    public String summary() {
        if (this.isEmpty()) {
            return "（未选择任何动作）";
        }
        StringBuilder sb = new StringBuilder();
        if ((this.actions & BIT_COLOR) != 0) {
            sb.append(RingColor.toHex(this.color))
                    .append(this.colorFade ? "（渐变）" : "（直接）");
        }
        if ((this.actions & BIT_ON) != 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("开灯").append(this.onFade ? "（淡入）" : "");
        }
        if ((this.actions & BIT_OFF) != 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("关灯").append(this.offFade ? "（淡出）" : "");
        }
        if ((this.actions & BIT_BLINK) != 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            // 0 = 一直闪（见 apply 里的说明）
            sb.append("闪烁").append(this.blinkTotal <= 0
                    ? "（持续）"
                    : (this.blinkTotal / 20.0D) + "s");
        }
        return sb.toString();
    }

    private static int clampTicks(int v, int min) {
        if (v < min) {
            return min;
        }
        return v > MAX_TICKS ? MAX_TICKS : v;
    }

    // ==================== NBT / 网络序列化 ====================

    private static final String K_ACTIONS = "Act";
    private static final String K_COLOR = "Color";
    private static final String K_CF = "CFade";
    private static final String K_CFT = "CFadeT";
    private static final String K_OF = "OFade";
    private static final String K_OFT = "OFadeT";
    private static final String K_FF = "FFade";
    private static final String K_FFT = "FFadeT";
    private static final String K_BP = "BPeriod";
    private static final String K_BT = "BTotal";
    private static final String K_BI = "BIn";
    private static final String K_BO = "BOut";

    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        c.setInteger(K_ACTIONS, this.actions);
        c.setInteger(K_COLOR, this.color & 0xFFFFFF);
        c.setBoolean(K_CF, this.colorFade);
        c.setInteger(K_CFT, this.colorFadeTicks);
        c.setBoolean(K_OF, this.onFade);
        c.setInteger(K_OFT, this.onFadeTicks);
        c.setBoolean(K_FF, this.offFade);
        c.setInteger(K_FFT, this.offFadeTicks);
        c.setInteger(K_BP, this.blinkPeriod);
        c.setInteger(K_BT, this.blinkTotal);
        c.setInteger(K_BI, this.blinkFadeIn);
        c.setInteger(K_BO, this.blinkFadeOut);
        return c;
    }

    public void readFromNBT(NBTTagCompound c) {
        this.actions = c.getInteger(K_ACTIONS);
        this.color = c.getInteger(K_COLOR) & 0xFFFFFF;
        this.colorFade = c.getBoolean(K_CF);
        this.colorFadeTicks = c.getInteger(K_CFT);
        this.onFade = c.getBoolean(K_OF);
        this.onFadeTicks = c.getInteger(K_OFT);
        this.offFade = c.getBoolean(K_FF);
        this.offFadeTicks = c.getInteger(K_FFT);
        this.blinkPeriod = c.getInteger(K_BP);
        this.blinkTotal = c.getInteger(K_BT);
        this.blinkFadeIn = c.getInteger(K_BI);
        this.blinkFadeOut = c.getInteger(K_BO);
    }

    public void writeTo(ByteBuf buf) {
        buf.writeInt(this.actions);
        buf.writeInt(this.color & 0xFFFFFF);
        buf.writeBoolean(this.colorFade);
        buf.writeShort(clampTicks(this.colorFadeTicks, 0));
        buf.writeBoolean(this.onFade);
        buf.writeShort(clampTicks(this.onFadeTicks, 0));
        buf.writeBoolean(this.offFade);
        buf.writeShort(clampTicks(this.offFadeTicks, 0));
        buf.writeShort(clampTicks(this.blinkPeriod, 0));
        buf.writeShort(clampTicks(this.blinkTotal, 0));
        buf.writeShort(clampTicks(this.blinkFadeIn, 0));
        buf.writeShort(clampTicks(this.blinkFadeOut, 0));
    }

    public void readFrom(ByteBuf buf) {
        this.actions = buf.readInt();
        this.color = buf.readInt() & 0xFFFFFF;
        this.colorFade = buf.readBoolean();
        this.colorFadeTicks = buf.readShort() & 0xFFFF;
        this.onFade = buf.readBoolean();
        this.onFadeTicks = buf.readShort() & 0xFFFF;
        this.offFade = buf.readBoolean();
        this.offFadeTicks = buf.readShort() & 0xFFFF;
        this.blinkPeriod = buf.readShort() & 0xFFFF;
        this.blinkTotal = buf.readShort() & 0xFFFF;
        this.blinkFadeIn = buf.readShort() & 0xFFFF;
        this.blinkFadeOut = buf.readShort() & 0xFFFF;
    }
}
