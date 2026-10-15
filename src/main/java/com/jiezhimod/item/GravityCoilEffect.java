package com.jiezhimod.item;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 重力线圈生效逻辑：手持时跳得更高（约 6 格）。
 *
 * 为什么不用「跳跃提升」药水效果：
 *  - 1.12.2 玩家没有可改的跳跃力属性（SharedMonsterAttributes 里没有 JUMP_STRENGTH），
 *    原版跳跃是 EntityLivingBase.jump() 里硬写 motionY = getJumpUpwardsMotion() = 0.42；
 *  - Forge 在 jump() 末尾抛出 LivingJumpEvent，此时 motionY 已设好（且已叠加跳跃提升药水），
 *    在这里直接改写 motionY 就是最干净的实现：无粒子、无 HUD 图标、不占用效果栏。
 *
 * 双端一致：客户端（本地玩家起跳）与服务端（NetHandlerPlayServer 处理跳跃包）都会调用
 * jump()，因此本事件两端都会触发，改出来的 motionY 完全一致，不会产生移动不同步/回弹。
 *
 * 叠加：事件里先取「原版已经加上的额外量」(motionY - 基础值)，再换成线圈初速度。
 * 于是「线圈 + 跳跃提升药水」= 线圈高度 + 药水那部分，比只用其中一个都更高。
 */
public class GravityCoilEffect {

    /** 原版跳跃初速度：EntityLivingBase.getJumpUpwardsMotion() 返回 0.42f */
    public static final double BASE_JUMP = (double) 0.42F;

    /**
     * 线圈跳跃初速度。按 MC 的离散物理反解得到：
     * 每 tick 先「y += v」，再「v = (v - 0.08) * 0.98」；
     * v = 0.42 时峰值 1.2522 格（与原版公认跳跃高度吻合，可作校准基准），
     * v = 1.0075 时峰值 6.00 格。
     */
    public static final double COIL_JUMP = 1.0075D;

    /** 每 tick 的重力增量（EntityLivingBase.travel 里的 0.08） */
    private static final double GRAVITY_PER_TICK = 0.08D;
    /** 竖直阻尼（travel 里的 0.9800000190734863） */
    private static final double VERTICAL_DRAG = 0.98D;

    @SubscribeEvent
    public void onLivingJump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        if (!CoilUtil.isHolding(player, ModItems.GRAVITY_COIL)) {
            return;
        }
        player.motionY = coilJumpVelocity(player.motionY);
    }

    /**
     * 线圈应当写入的跳跃初速度。
     *
     * 事件触发时原版已把 motionY 设成跳跃初速度（若喝了跳跃提升药水，那份额外量也已经加进去了），
     * 所以先取出「超出基础值的部分」再换成线圈初速度 —— 于是「线圈 + 药水」比两者单独用都高。
     *
     * @param vanillaMotionY 事件触发时的 motionY（原版已算好的值）
     */
    public static double coilJumpVelocity(double vanillaMotionY) {
        double extra = Math.max(0.0D, vanillaMotionY - BASE_JUMP);
        return COIL_JUMP + extra;
    }

    /** 给定跳跃初速度的峰值高度（格）：按 MC 的离散积分模拟 */
    public static double jumpHeight(double initialVelocity) {
        double y = 0.0D;
        double v = initialVelocity;
        while (v > 0.0D) {
            y += v;
            v = (v - GRAVITY_PER_TICK) * VERTICAL_DRAG;
        }
        return y;
    }

    /** 线圈跳跃高度（格），当前参数下约 6.00 */
    public static double coilJumpHeight() {
        return jumpHeight(COIL_JUMP);
    }

    /** 原版跳跃高度（格），用于对照，约 1.25 */
    public static double vanillaJumpHeight() {
        return jumpHeight(BASE_JUMP);
    }
}
