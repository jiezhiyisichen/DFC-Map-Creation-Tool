package com.jiezhimod.client;

import com.jiezhimod.client.particle.JetParticleFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 喷气背包的客户端表现：背包下方喷出尾焰粒子。
 *
 * 【为什么粒子必须客户端生成】
 * 服务端 world.spawnParticle 在客户端没有渲染表现（粒子是纯客户端渲染的），
 * 服务端只能用 S2P 包告诉客户端"现在在喷"，真正的粒子由本类在本地生成。
 */
@SideOnly(Side.CLIENT)
public final class ClientJetRenderer {

    /**
     * 自定义粒子 ID。
     *
     * 1.12.2 的 ParticleManager.particleTypes 是 Map&lt;Integer, IParticleFactory&gt;，
     * 原版粒子 ID 占 0..~50，取 2000 起保证不与任何 mod 撞车。
     */
    public static final int PARTICLE_ID = 2000;

    /** 每 tick 最多生成的粒子数，防止低端机/多人生意圈时刷屏 */
    private static final int MAX_PER_TICK = 2;

    /** 工厂是否已注册：effectRenderer 在 init 阶段还没建好，必须懒注册且只注册一次 */
    private static boolean factoryRegistered = false;

    private ClientJetRenderer() {
    }

    /** 玩家进入世界时把工厂注册进 ParticleManager（只做一次） */
    public static void registerParticleFactory(Minecraft mc) {
        if (factoryRegistered || mc == null || mc.effectRenderer == null) {
            return;
        }
        try {
            mc.effectRenderer.registerParticle(PARTICLE_ID, new JetParticleFactory());
            factoryRegistered = true;
        } catch (Throwable t) {
            // 与其他 mod 的粒子 ID 撞车时静默降级：飞行功能不受影响，只是没有尾焰
            com.jiezhimod.JiezhiMod.warn("Failed to register jet particle factory: {}", String.valueOf(t));
        }
    }

    /**
     * 客户端 tick：为所有正在喷气的玩家生成尾焰。
     */
    public static void onClientTick(Minecraft mc) {
        if (mc == null || mc.world == null || mc.player == null) {
            return;
        }
        // 懒注册：进世界后 effectRenderer 才可用
        registerParticleFactory(mc);
        if (ClientJetState.isFiring(mc.player) != Boolean.TRUE) {
            return;
        }
        spawnExhaust(mc, mc.player);
    }

    /** 在玩家背后喷一团尾焰 */
    public static void spawnExhaust(Minecraft mc, EntityPlayer player) {
        if (mc == null || mc.effectRenderer == null) {
            return;
        }
        ParticleManager pm = mc.effectRenderer;

        EnumFacing back = player.getHorizontalFacing().getOpposite();
        double bx = back.getFrontOffsetX();
        double bz = back.getFrontOffsetZ();

        for (int i = 0; i < MAX_PER_TICK; i++) {
            double ox = (Math.random() - 0.5D) * 0.34D;
            double oz = (Math.random() - 0.5D) * 0.34D;
            double px = player.posX - bx * 0.30D + ox;
            double py = player.posY + 0.10D + (Math.random() - 0.5D) * 0.08D;
            double pz = player.posZ - bz * 0.30D + oz;

            // 尾焰朝"下 + 后"喷：向下的分量最大，形成喷气柱
            double mx = -bx * 0.06D + (Math.random() - 0.5D) * 0.05D;
            double my = -0.22D - Math.random() * 0.10D;
            double mz = -bz * 0.06D + (Math.random() - 0.5D) * 0.05D;

            Particle p = pm.spawnEffectParticle(PARTICLE_ID, px, py, pz, mx, my, mz);
            if (p != null) {
                // spawnEffectParticle 在 ID 未注册时会返回 null，静默跳过即可
                // 1.12.2 的方法名是 multipleParticleScaleBy（不是 multiplyParticleSizeBy）
                p.multipleParticleScaleBy(0.8F);
            }
        }
    }
}
