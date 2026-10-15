package com.jiezhimod.client.particle;

import net.minecraft.client.particle.IParticleFactory;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.World;

/**
 * {@link ParticleJetExhaust} 的工厂。
 *
 * 1.12.2 没有 FXEvents.registerParticle（那是 1.13+），
 * 自定义粒子的唯一入口是把工厂注册进 ParticleManager 的 particleTypes 表，
 * 见 {@link JetParticleRegistry}。
 */
public class JetParticleFactory implements IParticleFactory {

    @Override
    public Particle createParticle(int id, World world, double x, double y, double z,
                                   double mx, double my, double mz, int... params) {
        return new ParticleJetExhaust(world, x, y, z, mx, my, mz);
    }
}
