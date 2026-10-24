package com.jiezhimod.repeater;

/**
 * 高级红石中继器的**计时状态机**（纯逻辑，不 import 任何 MC 类）。
 *
 * <p>状态只有两个：**输出**（亮/灭）和**计时器**（距离上次输入变化过了几刻）。</p>
 *
 * <pre>
 *   输入 == 输出  →  计时器清零（已经稳定，什么都不用做）
 *   输入 != 输出  →  计时器 + 1；一到 delay 就把输出跟上去，计时器清零
 * </pre>
 *
 * <p>这样正好复刻原版中继器的四条性质：</p>
 * <ul>
 *   <li><b>上升沿</b>：输入变高后要过满 <b>delay 刻</b>输出才变高；</li>
 *   <li><b>下降沿</b>：输入变低后同样要过满 <b>delay 刻</b>输出才变低（对称）；</li>
 *   <li><b>短脉冲被吞掉</b>：还没到 delay 输入就变回去了 ⇒ 输入又等于输出，
 *       计时器直接清零，输出从头到尾没动过；</li>
 *   <li><b>锁定</b>：外部不推进计时器，输出就冻在当下（由方块实体负责）。</li>
 * </ul>
 *
 * <p>【为什么不用"计数器 0~delay"那种写法】看起来更简单，但下降沿会少 1 刻：
 * 计数器从 delay 掉到 delay-1 就已经不通电了，而原版要等满 delay 刻。
 * 这个 1 刻的差在串联多个中继器时会累积成一眼能看出的时序错误，
 * 所以这里显式记住"输出"这个状态，而不是从计数器反推。</p>
 */
public final class RepeaterTimer {

    /** 距上次输入变化已经过了几刻（0 ~ delay） */
    private int elapsed;

    /** 当前输出（与方块状态的 POWERED 保持一致） */
    private boolean output;

    public RepeaterTimer() {
    }

    public boolean output() {
        return this.output;
    }

    public int elapsed() {
        return this.elapsed;
    }

    /**
     * 把内部状态对齐到外部（方块状态的 POWERED）。
     *
     * 区块重新加载 / 读档时内部计时器是从 0 开始的，而方块状态里还带着上次的亮灭，
     * 不先对齐的话第一刻就会误判成"输入变了"，让输出莫名其妙闪一下。
     */
    public void syncOutput(boolean state) {
        this.output = state;
        this.elapsed = 0;
    }

    /**
     * 推进一刻。
     *
     * @return 输出是否**翻转**（调用方据此决定要不要写方块状态 + 通知邻居）
     */
    public boolean tick(boolean input, int delayTicks) {
        int delay = delayTicks < RepeaterDelay.MIN_TICKS ? RepeaterDelay.MIN_TICKS : delayTicks;
        if (input == this.output) {
            this.elapsed = 0;
            return false;
        }
        this.elapsed++;
        if (this.elapsed >= delay) {
            this.output = input;
            this.elapsed = 0;
            return true;
        }
        return false;
    }

    /** 延迟变短时把计时器夹进新区间（否则从 60 秒改成 1 tick 还要等 60 秒才反应） */
    public void clampDelay(int delayTicks) {
        if (this.elapsed > delayTicks) {
            this.elapsed = delayTicks;
        }
        if (this.elapsed < 0) {
            this.elapsed = 0;
        }
    }
}
