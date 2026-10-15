package com.jiezhimod.item;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 音效注册（拿起加速线圈时播放的那一下）。
 *
 * SoundEvent 必须在服务端也有注册条目：服务端负责广播 SPacketSoundEffect，
 * 客户端负责按 sounds.json 去加载 assets/jiezhimod/sounds/coil_equip.ogg。
 * 两侧资源名必须一致，否则客户端会出现「找不到音效」静默失败。
 */
@Mod.EventBusSubscriber(modid = com.jiezhimod.JiezhiMod.MODID)
public class ModSounds {

    /** 拿起加速线圈的一次性提示音 */
    public static final SoundEvent COIL_EQUIP =
            new SoundEvent(new ResourceLocation(com.jiezhimod.JiezhiMod.MODID, "coil_equip"))
                    .setRegistryName("jiezhimod", "coil_equip");

    @SubscribeEvent
    public static void onRegisterSounds(RegistryEvent.Register<SoundEvent> event) {
        event.getRegistry().register(COIL_EQUIP);
    }
}
