package com.jiezhimod.client.ring;

import com.jiezhimod.block.TileLightRing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.Collection;

/**
 * 灯圈的**彩色光晕**（v4.4）—— 让灯周围"看起来真的被染上了颜色"。
 *
 * <h3>为什么必须自己画（先把这个说清楚）</h3>
 * 1.12.2 原版的方块光**只有亮度、没有颜色**：
 * {@code EntityRenderer} 生成光照贴图时调用
 * {@code world.provider.getLightmapColors(...)} 拿到的是**一个全局 RGB**，
 * 再按每格亮度（0~15）去索引一张 16×16 的渐变表。
 * 也就是说同一个世界里所有方块光共用同一种颜色 —— 想让"这一格的光变红"，
 * **mod 侧没有任何接口**（彩色方块光一直是 OptiFine Colored Lights / 光影包的能力）。
 *
 * <p>所以这里走的是所有"彩色光"mod 的通用办法：**在方块外面画一层彩色半透明壳**，
 * 加法混合叠在地形上，视觉上就是"光从灯圈里溢出来把周围染了色"。
 * 它不改变真实光照值、不照亮实体，但玩家看到的就是那个样子。</p>
 *
 * <h3>几个刻意的取舍</h3>
 * <ul>
 *   <li><b>只画"裙边"</b>：每个面从方块边缘沿法线外扩 {@link #OUT} 格，
 *       内缘 alpha 有值、外缘 alpha = 0。不做整块放大的立方体 ——
 *       那样等于给方块罩一层彩色雾，边缘会有一条硬边。</li>
 *   <li><b>加法混合</b>（{@code SRC_ALPHA, ONE}）：光叠光会变亮，两条灯带挨着时
 *       接缝自然变亮，正是真实光源的观感。</li>
 *   <li><b>只关深度写入、不关深度测试</b>：挡在墙后的那半边自动看不见，
 *       不用自己做遮挡剔除。</li>
 *   <li><b>alpha 跟亮度走</b>：颜色本身已经是压暗过的（{@code RingState.colorAt} 里
 *       乘过 {@code brightAt}），再乘一次最大分量 —— 灭掉的灯圈 alpha 接近 0，
 *       不需要额外的开关判断。</li>
 * </ul>
 *
 * <p>放在 {@code com.jiezhimod.client.**} 里是硬要求：{@code proguard.pro} 整包保留这个前缀，
 * 事件方法被改名的话会**静默失效**（不报错、不写日志、画面什么都没有）。</p>
 */
@SideOnly(Side.CLIENT)
public class RingGlowRenderer {

    /** 超出这个距离就不画了（格）。光晕在远处本来就看不见，省 GPU */
    private static final double MAX_DIST_SQ = 48.0D * 48.0D;
    /** 光往方块外溢出的距离（格） */
    private static final double OUT = 0.55D;
    /** 贴面处的 alpha 上限（外缘一律 0） */
    private static final float A_MAX = 0.38F;
    /** 一次最多画多少格（防止上千格的灯墙把顶点缓冲撑爆） */
    private static final int MAX_RINGS = 320;

    /**
     * 光影（OptiFine shader）开着时的 alpha 补偿系数。
     *
     * <h3>为什么需要</h3>
     * 光影包会在 composite 阶段整屏重新做色调映射与曝光，自绘的半透明几何
     * 很容易被压暗、甚至看着"没画"。这里在检测到 shader 生效时把 alpha 提上去一点。
     *
     * <p>【必须反射】OptiFine 不一定装（也常常是启动器层 tweaker 而不是 mods 里的 jar），
     * 直接 {@code import net.optifine.shaders.Shaders} 在没装的时候会
     * {@code NoClassDefFoundError} 把整个渲染钩子带崩。</p>
     */
    private static final float SHADER_BOOST = 1.45F;

    /** null = 还没探测过；缓存一下，避免每帧都反射 */
    private static Boolean shaderPackLoaded;

    /** OptiFine 光影是否生效（没装 OptiFine / 没开光影都返回 false） */
    private static boolean shadersActive() {
        Boolean cached = shaderPackLoaded;
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean on = false;
        try {
            Class<?> shaders = Class.forName("net.optifine.shaders.Shaders");
            Object v = shaders.getField("shaderPackLoaded").get(null);
            on = Boolean.TRUE.equals(v);
        } catch (Throwable ignored) {
            // 没装 OptiFine，或者版本里字段名不同 —— 都按"没有光影"处理
            on = false;
        }
        shaderPackLoaded = Boolean.valueOf(on);
        return on;
    }

    /** 用户中途开关光影时要重新探测（每 100 帧看一次，代价可以忽略） */
    private static void refreshShaderCache() {
        if ((tickCounter++ % 100) == 0) {
            shaderPackLoaded = null;
        }
    }

    private static int tickCounter;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        Entity view = mc.getRenderViewEntity();
        if (world == null || view == null) {
            return;
        }
        Collection<TileLightRing> all = TileLightRing.clientAll();
        if (all.isEmpty()) {
            return;
        }

        float pt = event.getPartialTicks();
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;

        refreshShaderCache();
        float boost = shadersActive() ? SHADER_BOOST : 1.0F;

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        boolean cullWasOn = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        // 加法混合：多盏灯的光叠在一起会更亮，而不是互相覆盖
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE);
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.translate(-cx, -cy, -cz);

        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        int drawn = 0;
        for (TileLightRing t : all) {
            if (drawn >= MAX_RINGS) {
                break;
            }
            if (t == null || t.isInvalid() || t.getWorld() != world) {
                continue;
            }
            double dx = t.getPos().getX() + 0.5D - cx;
            double dy = t.getPos().getY() + 0.5D - cy;
            double dz = t.getPos().getZ() + 0.5D - cz;
            if (dx * dx + dy * dy + dz * dz > MAX_DIST_SQ) {
                continue;
            }
            int rgb = t.displayColor();
            float r = ((rgb >> 16) & 0xFF) / 255.0F;
            float g = ((rgb >> 8) & 0xFF) / 255.0F;
            float b = (rgb & 0xFF) / 255.0F;
            // 灭态的颜色本身已经被压暗过，这里再乘一次最大分量 → alpha 自然趋近 0
            float lum = Math.max(r, Math.max(g, b));
            float a = Math.min(0.85F, A_MAX * lum * boost);
            if (a < 0.015F) {
                continue;
            }
            shell(buf, t.getPos().getX(), t.getPos().getY(), t.getPos().getZ(), r, g, b, a);
            drawn++;
        }

        tess.draw();

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.depthMask(depthMask);
        if (cullWasOn) {
            GlStateManager.enableCull();
        }
        GlStateManager.popMatrix();
    }

    /**
     * 给一格灯圈画 6 个面的"光裙子"。
     *
     * <p>每个面取 4 个角，沿法线外扩 {@link #OUT} 得到 4 个外角，再沿 4 条边各出一个梯形：
     * 内缘（贴面）alpha = {@code a}，外缘 alpha = 0。相邻两个面的梯形在棱上共用一条边，
     * 加法混合下棱会稍微亮一点 —— 看起来正好像"光从棱角溢出"。</p>
     */
    private static void shell(BufferBuilder buf, int bx, int by, int bz,
                              float r, float g, float b, float a) {
        for (EnumFacing f : EnumFacing.values()) {
            Vec3i n = f.getDirectionVec();
            // 面中心（方块局部 0..1）
            double mx = 0.5D + n.getX() * 0.5D;
            double my = 0.5D + n.getY() * 0.5D;
            double mz = 0.5D + n.getZ() * 0.5D;
            // 面内两个切向轴（挑两个与法线不平行的）
            Vec3i u;
            Vec3i v;
            if (n.getY() != 0) {
                u = new Vec3i(1, 0, 0);
                v = new Vec3i(0, 0, 1);
            } else if (n.getX() != 0) {
                u = new Vec3i(0, 1, 0);
                v = new Vec3i(0, 0, 1);
            } else {
                u = new Vec3i(1, 0, 0);
                v = new Vec3i(0, 1, 0);
            }
            // 4 个角（面内，顺时针）
            double[][] corner = new double[4][3];
            int[][] signs = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int i = 0; i < 4; i++) {
                corner[i][0] = mx + signs[i][0] * 0.5D * u.getX() + signs[i][1] * 0.5D * v.getX();
                corner[i][1] = my + signs[i][0] * 0.5D * u.getY() + signs[i][1] * 0.5D * v.getY();
                corner[i][2] = mz + signs[i][0] * 0.5D * u.getZ() + signs[i][1] * 0.5D * v.getZ();
            }
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) & 3;
                // 内缘两点（贴面，有 alpha）
                buf.pos(bx + corner[i][0], by + corner[i][1], bz + corner[i][2])
                        .color(r, g, b, a).endVertex();
                buf.pos(bx + corner[j][0], by + corner[j][1], bz + corner[j][2])
                        .color(r, g, b, a).endVertex();
                // 外缘两点（外扩，alpha = 0）
                buf.pos(bx + corner[j][0] + n.getX() * OUT,
                        by + corner[j][1] + n.getY() * OUT,
                        bz + corner[j][2] + n.getZ() * OUT)
                        .color(r, g, b, 0.0F).endVertex();
                buf.pos(bx + corner[i][0] + n.getX() * OUT,
                        by + corner[i][1] + n.getY() * OUT,
                        bz + corner[i][2] + n.getZ() * OUT)
                        .color(r, g, b, 0.0F).endVertex();
            }
        }
    }
}
