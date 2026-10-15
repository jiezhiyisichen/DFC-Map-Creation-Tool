package com.jiezhimod.item;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 喷气飞行：服务端权威的每 tick 逻辑。
 *
 * 流程：
 *   1) 每 tick 判断「胸甲槽是喷气背包」且「主/副手拿着开关」且「按住右键」；
 *   2) 交给 {@link JetHeat} 推进一步热量状态机；
 *   3) FIRING 时按 {@link JetPhysics} 施加升力 + 水平推进，并通知客户端喷粒子；
 *   4) 松开右键 / 过热 → 不再施加任何动力，只降温。
 *
 * 【为什么用 PlayerTickEvent 而不是 LivingJumpEvent】
 * 跳跃事件只在"起跳那一瞬间"触发，无法维持悬停；飞行必须每 tick 持续改速度。
 *
 * 【双端一致性】
 * 服务端改 motion 会同步到客户端；但客户端本地预测也会算一遍重力，
 * 所以这里**同时在客户端也执行同样的推力**（见 onClientTick），
 * 否则本地画面会先掉下去再被服务端拉回，出现明显的"顿挫/回弹"。
 */
public class JetpackEffect {

    /** 每个玩家的热量状态：key = 玩家 UUID */
    private static final Map<UUID, JetHeat> HEAT_MAP = new HashMap<UUID, JetHeat>();

    /** 上一 tick 是否在喷气，用于给客户端发"开始/停止喷气"的网络包 */
    private static final Map<UUID, Boolean> FIRING_PREV = new HashMap<UUID, Boolean>();

    /**
     * 事件总线需要一个**公共无参构造器**才能实例化注册。
     * 本类没有实例状态（全是静态的），所以构造器是空的。
     */
    public JetpackEffect() {
    }

    // ==================== 状态查询 ====================

    /** 取（或懒创建）某玩家的热量状态 */
    public static JetHeat heatOf(EntityPlayer player) {
        UUID id = player.getUniqueID();
        JetHeat h = HEAT_MAP.get(id);
        if (h == null) {
            h = new JetHeat();
            HEAT_MAP.put(id, h);
        }
        return h;
    }

    public static JetHeat heatOf(UUID id) {
        JetHeat h = HEAT_MAP.get(id);
        if (h == null) {
            h = new JetHeat();
            HEAT_MAP.put(id, h);
        }
        return h;
    }

    /** 玩家离线时清掉，避免 map 无限增长 */
    public static void forget(UUID id) {
        HEAT_MAP.remove(id);
        FIRING_PREV.remove(id);
    }

    public static void clearAll() {
        HEAT_MAP.clear();
        FIRING_PREV.clear();
    }

    // ==================== 条件判定 ====================

    /** 胸甲槽是否穿着喷气背包 */
    public static boolean hasJetpack(EntityPlayer player) {
        ItemStack chest = player.getItemStackFromSlot(
                net.minecraft.inventory.EntityEquipmentSlot.CHEST);
        return chest != null && !chest.isEmpty() && chest.getItem() == ModItems.JETPACK;
    }

    /** 主手或副手是否拿着喷气开关 */
    public static boolean hasSwitch(EntityPlayer player) {
        ItemStack main = player.getHeldItemMainhand();
        ItemStack off = player.getHeldItemOffhand();
        return isSwitch(main) || isSwitch(off);
    }

    public static boolean isSwitch(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == ModItems.JET_SWITCH;
    }

    /** 玩家这一 tick 是否想喷气：装备齐全 + 按住右键 */
    public static boolean wantsFire(EntityPlayer player) {
        if (!hasJetpack(player) || !hasSwitch(player)) {
            return false;
        }
        return isUsingSwitch(player);
    }

    /**
     * 是否正按住右键。
     *
     * 直接用 rightClickDelayTimer 不够稳（它会被连续点击重置），
     * 这里用「本 tick 收到过右键使用」+「还没到抬起」判断：
     * EntityPlayer 的 isHandActive() 正是"持续按住右键"的官方状态。
     */
    private static boolean isUsingSwitch(EntityPlayer player) {
        return player.isHandActive() && player.getActiveItemStack() != ItemStack.EMPTY
                && isSwitch(player.getActiveItemStack());
    }

    // ==================== 服务端主循环 ====================

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != net.minecraftforge.fml.relauncher.Side.SERVER) {
            return;
        }
        EntityPlayer player = event.player;
        if (player == null || player.world == null) {
            return;
        }
        stepPlayer(player);
    }

    /**
     * 推进一个玩家的喷气状态并施加动力。
     *
     * 【服务端 / 客户端的分工（关键）】
     * 1.12.2 的 {@code movementInput} 字段**只存在于客户端**
     * （javap 确认在 EntityPlayerSP 上，EntityPlayer 上没有），
     * 服务端直接读会编译失败、运行期也不成立。
     * 所以：
     *   · 服务端只按"按住右键"施加**竖直升力 + 朝向前进**（用 getHorizontalFacing），
     *     这样即使客户端没做本地预测，权威结果也一定是"在飞"；
     *   · 客户端拿到 movementInput 后再叠加 WASD 的横向推进与上升，
     *     让本地操作手感跟手（不然会明显延迟一两个包）。
     */
    public static void stepPlayer(EntityPlayer player) {
        World w = player.world;
        if (w == null) {
            return;
        }
        JetHeat heat = heatOf(player);
        boolean want = wantsFire(player);
        JetHeat.State st = heat.tick(want);

        boolean firing = st == JetHeat.State.FIRING;
        boolean prev = Boolean.TRUE.equals(FIRING_PREV.get(player.getUniqueID()));
        if (firing != prev) {
            FIRING_PREV.put(player.getUniqueID(), firing);
            sendJetState(player, firing, heat.getHeatRatio());
        } else if (firing) {
            // 飞行中持续同步热量，供热量条平滑显示
            sendJetState(player, true, heat.getHeatRatio());
        }

        if (!firing) {
            return;
        }

        // 服务端：用朝向提供基础前进，竖直方向做悬停/爬升
        double forward = facingForward(player);
        double strafe = 0.0D;
        boolean rising = w.isRemote && isClientRising(player);
        double up = rising ? 1.0D : 0.0D;

        double[] cur = {player.motionX, player.motionY, player.motionZ};
        double[] next = JetPhysics.step(cur, forward, strafe, up, rising);
        player.motionX = next[0];
        player.motionY = next[1];
        player.motionZ = next[2];

        // 喷气时清掉下落距离，避免高速下落被摔死
        player.fallDistance = 0.0F;
    }

    /** 朝视线方向的前进分量（-1..1）：服务端没有 movementInput，用朝向近似 */
    private static double facingForward(EntityPlayer player) {
        double fx = -Math.sin(Math.toRadians(player.rotationYaw)) * Math.cos(Math.toRadians(player.rotationPitch));
        double fz = Math.cos(Math.toRadians(player.rotationYaw)) * Math.cos(Math.toRadians(player.rotationPitch));
        if (fx * fx + fz * fz < 1.0E-6D) {
            return 0.0D;
        }
        return 1.0D;
    }

    /**
     * 把喷气状态发给该玩家的客户端。
     *
     * SimpleNetworkWrapper.sendTo 只接受 EntityPlayerMP（服务端实体），
     * 而事件里拿到的是 EntityPlayer，所以这里做一次类型判断后再发。
     */
    private static void sendJetState(EntityPlayer player, boolean firing, float heatRatio) {
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        com.jiezhimod.network.ModNetwork.CHANNEL.sendTo(
                new com.jiezhimod.network.msg.MessageJetExhaust(player, firing, heatRatio),
                (EntityPlayerMP) player);
    }

    // ==================== 客户端同步 ====================

    /**
     * 客户端同样每 tick 施加推力，避免"服务端拉回"造成的视觉顿挫。
     *
     * 这里才能读 movementInput（该字段只在 EntityPlayerSP 上存在）。
     * 客户端**不**推进热量状态 —— 那是服务端权威，由同步包下发。
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || event.side != net.minecraftforge.fml.relauncher.Side.CLIENT) {
            return;
        }
        EntityPlayer player = event.player;
        if (player == null || player.world == null) {
            return;
        }
        // 1.12.2 的 Minecraft 没有 isLocalPlayer()，用引用比较判断本机玩家
        if (player != Minecraft.getMinecraft().player) {
            return;
        }
        Boolean firing = com.jiezhimod.client.ClientJetState.isFiring(player);
        if (firing == null || !firing) {
            return;
        }
        boolean rising = isClientRising(player);
        double forward = 0.0D;
        double strafe = 0.0D;
        if (player instanceof net.minecraft.client.entity.EntityPlayerSP) {
            net.minecraft.util.MovementInput in =
                    ((net.minecraft.client.entity.EntityPlayerSP) player).movementInput;
            if (in != null) {
                forward = in.moveForward;
                strafe = in.moveStrafe;
            }
        }
        double up = rising ? 1.0D : 0.0D;
        double[] cur = {player.motionX, player.motionY, player.motionZ};
        double[] next = JetPhysics.step(cur, forward, strafe, up, rising);
        player.motionX = next[0];
        player.motionY = next[1];
        player.motionZ = next[2];
        player.fallDistance = 0.0F;
    }

    /** 客户端读跳跃键（movementInput 只在客户端存在，必须先做类型判断） */
    private static boolean isClientRising(EntityPlayer player) {
        if (!(player instanceof net.minecraft.client.entity.EntityPlayerSP)) {
            return false;
        }
        net.minecraft.util.MovementInput in =
                ((net.minecraft.client.entity.EntityPlayerSP) player).movementInput;
        return in != null && in.jump;
    }

    // ==================== 工具 ====================

    /** 玩家背后（喷口位置）：略低于肩、朝向与视线相反 */
    public static Vec3d exhaustPos(EntityPlayer player) {
        EnumFacing back = player.getHorizontalFacing().getOpposite();
        double bx = back.getFrontOffsetX();
        double bz = back.getFrontOffsetZ();
        return new Vec3d(
                player.posX - bx * 0.30D,
                player.posY + 0.15D,
                player.posZ - bz * 0.30D);
    }
}
