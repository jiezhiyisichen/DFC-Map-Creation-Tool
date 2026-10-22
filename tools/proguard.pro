# Jiezhi mod ProGuard 混淆配置
# 策略：只混淆类名/成员名（dontshrink/dontoptimize），保留 Forge/FML 反射入口，最大限度兼容
#
# 本配置**不含任何机器专属的绝对路径**，可跨机器使用：
#   · injars / outjars 用相对路径 —— **相对本配置文件所在目录（tools/）**解析，
#     所以无论从哪里执行都成立：
#       java -jar tools/proguard-7.4.2/lib/proguard.jar @tools/proguard.pro
#   · libraryjars 用 <user.home> 占位，Gradle 缓存目录按 artifact 版本固定，
#     换机器同样成立（首次构建前先跑一次 gradlew 让缓存就绪）。

-injars ../build/libs/jiezhi-1.12.2-dfcmaptools-3.9.jar
-outjars obf.jar

-libraryjars <user.home>/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.12.2-14.23.5.2864_mapped_snapshot_20171003-1.12/forge-1.12.2-14.23.5.2864_mapped_snapshot_20171003-1.12.jar
-libraryjars <user.home>/.gradle/caches/modules-2/files-2.1/io.netty/netty-all/4.1.9.Final/97860965d6a0a6b98e7f569f3f966727b8db75/netty-all-4.1.9.Final.jar
-libraryjars <user.home>/.gradle/caches/modules-2/files-2.1/org.apache.logging.log4j/log4j-api/2.15.0/4a5aa7e55a29391c6f66e0b259d5189aa11e45d0/log4j-api-2.15.0.jar
-libraryjars <user.home>/.gradle/caches/modules-2/files-2.1/com.google.guava/guava/21.0/3a3d111be1be1b745edfa7d91678a12d7ed38709/guava-21.0.jar

-dontshrink
-dontoptimize
-ignorewarnings
# 注意：不能加 -dontpreverify！
# gradle 产物经 reobfJar 后 StackMapTable 丢失，必须让 ProGuard 重新生成
# （JVM8 对版本52类有分支的方法强制要求 stackmap frame，否则 VerifyError 崩溃）

# 保留注解、签名（FML 依赖反射）
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions

# Forge Mod 主类：FML 通过 @Mod 注解加载，保留类及全部成员
-keep @net.minecraftforge.fml.common.Mod class com.jiezhimod.JiezhiMod { *; }

# @Mod.EventHandler 方法（preInit/init/serverStarting/serverStopping）
-keepclassmembers class * {
    @net.minecraftforge.fml.common.Mod.EventHandler <methods>;
}

# @SubscribeEvent 方法（Forge 事件总线注册；类名也混淆）
-keepclassmembers class * {
    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent <methods>;
}

# @SidedProxy 通过字符串类名实例化代理（client/server 均为字符串），类名与成员不可混淆
-keep class com.jiezhimod.proxy.ClientProxy, com.jiezhimod.proxy.CommonProxy { *; }

# 物品 / 创造页：ItemSpeedCoil 覆写的 Item.addInformation、匿名 CreativeTabs 覆写的
# getTabIconItem 都是被 MC 虚调用的覆写方法。forge universal jar 内不含 net.minecraft.*，
# ProGuard 解析不到继承关系会把这些方法当普通方法改名 → 覆写断裂（tooltip 不显示、
# 物品页图标异常）。故整个 item 包显式保留。
# 喷漆背包同样在此列：ItemJetpack extends ItemArmor，覆写 addInformation。
-keep class com.jiezhimod.item.** { *; }

# 喷气粒子：ParticleJetExhaust extends Particle，覆写 onUpdate；
# JetParticleFactory implements IParticleFactory（由 ParticleManager 反查调用）。
# 两者都靠"按签名/接口被 MC 回调"，类名与成员不可混淆。
-keep class com.jiezhimod.client.particle.** { *; }

# 方块：BlockDelay 覆写 Block 的 createNewTileEntity / onBlockActivated / getWeakPower 等，
# TileDelay 实现 ITickable，同理必须整包保留，否则红石与 GUI 全线失效。
-keep class com.jiezhimod.block.** { *; }

# ============================================================================
# ★★ 整包保留 com.jiezhimod.client.**  —— 别再拆成一个个子包！
#
# 【为什么必须整包】ProGuard 的 libraryjars 里**解析不到 net.minecraft.***
# （forge universal jar 不含这些类），于是它根本不知道
# `RenderKeypad.render(...)` 是在覆写 `TileEntitySpecialRenderer.render(...)`。
# 结果它把这个方法改名成 `a(...)`，再补一个 `a(TileEntity,...)` 的桥接方法 ——
# 但基类那个 `render` 一点没变，MC 调的是基类的方法体（空的）：
#   * TESR 静默永不执行
#   * 日志里一个字都不报（不是异常，是根本没进这个方法）
#   * 方块模型照常显示，所以看起来"方块在，只是面板没画"
#
# 这个坑已经连续吃掉了三个版本（v3.9 / v3.9.2 / v3.9.6 的"密码盘不可见"），
# 同期还把 `GuiPasswordConsole` 的 initGui / drawGuiContainerBackgroundLayer
# 一起改名 → 3.9.2~3.9.6 的"右键控制台有鼠标但界面一片空白"也是它。
#
# 教训：**新增任何一个 client 子包都必须保留**，而"逐个列子包"这种事迟早会漏。
# 所以这里直接整包保留。obfuscation 的真正价值在 com.jiezhimod.dfc / keypad /
# network 这些纯逻辑包上，client 层本来也不需要藏。
# ============================================================================
-keep class com.jiezhimod.client.** { *; }

# 颜色码解码：纯工具，指令与 UI 都用到
-keep class com.jiezhimod.util.** { *; }

# Seal 完整性校验类：JiezhiMod 直接引用 + 构建脚本后注入编译，类名与 verify 签名保持
-keep class com.jiezhimod.seal.SealA, com.jiezhimod.seal.SealB {
    public static void verify();
}

# Minecraft 指令接口：CommandHandler 以接口方法调用，签名必须保留
-keepclassmembers class * extends net.minecraft.command.CommandBase {
    public *;
    protected *;
}

# 网络消息：SimpleNetworkWrapper 经接口反射调用
-keepclassmembers class * implements net.minecraftforge.fml.common.network.simpleimpl.IMessage {
    public *;
}
-keepclassmembers class * implements net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler {
    public *;
}
