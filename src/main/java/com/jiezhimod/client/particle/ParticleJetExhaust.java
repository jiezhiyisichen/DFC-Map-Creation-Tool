package com.jiezhimod.client.particle;

import net.minecraft.client.particle.Particle;
import net.minecraft.world.World;

/**
 * 喷气背包尾焰粒子（客户端专属）。
 *
 * 观感设计：沿生命从"亮白橙"渐变到"暗红"，尺寸先膨胀后收缩，
 * 并带一点向下重力 —— 看起来像有重量的高压气流，而不是笔直上升的烟。
 *
 * 【1.12.2 的两个 API 坑（都靠 javap 核对过，别再改回去）】
 *  1) 颜色不是 setColor()，而是 setRBGColorF()（R-B-G 这个原版历史拼写）。
 *  2) **没有 getFXLight()** —— 那是 SRG 映射名，映射后的 jar 里不存在，
 *     自发光只能靠 particleRed/Green/Blue 本身给足亮度。
 *
 * 【为什么必须自定义粒子而不是复用原版 EnumParticleTypes】
 * 1.12.2 的 EnumParticleTypes 是**封闭枚举**（javap 确认没有 register 方法），
 * 无法在运行时扩展；FXEvents.registerParticle 是 1.13+ 才有的 API。
 * 1.12.2 的正确做法是把自定义 ID 注册进 ParticleManager 的 particleTypes 表
 * （那张表是 Map&lt;Integer, IParticleFactory&gt;，按 ID 索引，不是枚举），
 * 由 {@link JetParticleFactory} + {@link com.jiezhimod.client.ClientJetRenderer} 完成注册。
 */
public class ParticleJetExhaust extends Particle {

    /** 粒子贴图表是 4x4，本粒子只用左上角那一格 */
    private static final int TEX_INDEX = 0;

    /** 基准尺寸：每 tick 的缩放都以它为基准，避免"逐 tick 叠乘"导致尺寸指数暴涨 */
    private final float baseScale;

    public ParticleJetExhaust(World worldIn, double x, double y, double z,
                              double mx, double my, double mz) {
        super(worldIn, x, y, z, mx, my, mz);
        this.particleTextureIndexX = TEX_INDEX;
        this.particleTextureIndexY = TEX_INDEX;

        // 寿命 12~18 tick：太短看着像"闪一下"，太长会糊住视野
        this.particleMaxAge = 12 + this.rand.nextInt(7);
        this.particleAge = 0;

        // 轻微向下重力：给尾焰"往下坠"的重量感
        this.particleGravity = -0.035F;

        this.baseScale = 0.9F + this.rand.nextFloat() * 0.5F;
        this.particleScale = this.baseScale;

        // 外部给的是喷口方向的速度，这里先减半再撒一点随机散射
        this.motionX *= 0.5D;
        this.motionZ *= 0.5D;
        this.motionY *= 0.5D;
        this.motionX += (this.rand.nextFloat() - 0.5F) * 0.06D;
        this.motionZ += (this.rand.nextFloat() - 0.5F) * 0.06D;

        // 颜色字段在构造时给满，避免第一帧是纯白
        this.particleRed = 1.0F;
        this.particleGreen = 0.85F;
        this.particleBlue = 0.5F;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        float t = (float) this.particleAge / (float) this.particleMaxAge;
        // 亮白橙 -> 暗红（直接写字段：1.12.2 没有 setColor）
        this.particleRed = 1.0F;
        this.particleGreen = (float) (0.85D - 0.55D * t);
        this.particleBlue = (float) (0.50D - 0.42D * t);
        // 始终以 baseScale 为基准做"先涨后缩"，而不是在上一帧的缩放上继续乘
        this.particleScale = this.baseScale * (1.0F + 0.35F * (float) Math.sin(t * Math.PI));
    }
}
