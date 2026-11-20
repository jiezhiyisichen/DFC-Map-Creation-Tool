package com.jiezhimod.client.render;

import com.jiezhimod.block.BlockAlarmLight;
import com.jiezhimod.block.TileAlarmLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.lwjgl.opengl.GL11;

/**
 * 旋转警示灯的渲染器（v4.5）—— 底座 + 旋转灯芯 + 圆灯罩 + 扫出去的光锥 + 落点光斑。
 *
 * <h3>为什么不给方块贴图</h3>
 * 16×16 的像素材质画圆只能得到锯齿圈，而需求是「完美的圆」。所以这里<b>全部现画几何</b>：
 * 圆柱按 {@link #SIDES} 边形细分，{@link #SIDES}=48 时一格宽度上是 48 段，
 * 肉眼已经完全看不出折线。
 *
 * <h3>光效去哪儿了（v4.15 定案）</h3>
 * 1.12.2 的原版方块光只有亮度、没有颜色（见 {@code RingGlowRenderer} 的说明），
 * 所以「一束光扫过墙面、把墙照出颜色」这件事不可能靠 {@code getLightValue} 做出来。
 *
 * <p>历史上这里前后画过三层东西，现在<b>全部移除</b>，只留灯体本身：</p>
 * <ul>
 *   <li>v4.8 的 3D 体积光柱 —— 真实警示灯在空气里看不见光束，删；</li>
 *   <li>v4.10 的网格射线光斑 —— 会穿模；</li>
 *   <li>v4.11~4.14 的 {@code drawGroundSpot} 面片光斑 —— 和屏幕空间聚光灯（SSS）
 *       叠在一起时会出现「柔和光照 + 一圈生硬红圈」，所以 v4.15 直接删掉。</li>
 * </ul>
 *
 * <p>现在真正的光照由 {@code DepthDebugPass} 的屏幕空间聚光灯负责，
 * 方向唯一来源是 {@link #beamWorldDir}。本类只负责画出灯的外壳、反光板与灯罩。</p>
 *
 * <h3>朝向怎么处理</h3>
 * 不依赖 {@code GlStateManager.rotate} 的欧拉角顺序，而是先算一组正交基
 * （灯轴 {@code axis} + 两个径向 {@code u/v}），所有局部坐标一律
 * {@code world = axis*y + u*x + v*z} 变换。反光板和光锥共用同一组基，
 * 不会出现「锥体朝东、影子朝西」这种对不上的情况。
 */
public class RenderAlarmLight extends TileEntitySpecialRenderer<TileAlarmLight> {

    /** 圆周细分段数。32 已经看不出多边形，48 更稳（一格 48 段） */
    private static final int SIDES = 48;
    /**
     * 光束起点在灯轴上的位置（±0.5 = 方块的进出两个面）。
     *
     * <p>★ v4.17：从 {@code -0.02}（几乎就是方块几何中心）改成 {@code +0.5}。
     * 挂壁安装时，灯体中心离墙面只有半格，光锥等于从灯罩<b>内部</b>点亮，
     * 打出去的光斑会整体偏向墙里一侧；推到灯口外面的那一面之后，光才真正
     * "从灯的侧面射出"。顺带好处：射线起点不再落在方块内部，
     * {@code rayTraceBlocks} 第一次命中不会再是灯自己。</p>
     */
    private static final double BEAM_ORIGIN_Y = 0.5D;
    /**
     * 抬离径向平面的角度（度）：-5 = 微微朝下，平地上放也能扫到地面。
     *
     * <p>★ <b>只对 {@link LightType#FLOOR} 生效</b>。挂壁 / 吊装各走自己的分支，
     * 这条完全不参与 —— 详见 {@link LightType}。</p>
     */
    private static final double BEAM_PITCH = -5.0D;
    /**
     * 光轴上再额外下压的分量（单位向量系），让光束几格内就落到地面而不是平飞。
     *
     * <p>同上，<b>只对立杆生效</b>。</p>
     */
    static final double BEAM_DROP = 0.20D;

    /** 墙面平面内的"向下"切向：{@code V = (0,-1,0)}，挂壁画圆的下垂基准 */
    private static final Vec3d WALL_DOWN = new Vec3d(0.0D, -1.0D, 0.0D);

    /**
     * 吊装（{@link LightType#CEILING}）光束的径向倾斜量 —— <b>必须是 0</b>。
     *
     * <h3>★★ v5.0.0：从 0.45 改为 0（吊顶灯只许垂直向下）</h3>
     * v5.0.0-beta 用 0.45 的径向倾斜让吊装灯"看得出在转"，但那是<b>探照灯</b>的行为，
     * 不是吊灯：吊在天花板上的灯就该把光往脚下倒，光斑是灯正下方的一个圆。
     *
     * <p>而且 0.45 会带来一个更实际的问题：吊装灯几乎总是装在<b>走道正上方</b>，
     * 斜出去的光一半打在墙面、一半打在旁边的机器上，玩家看到的是"灯在乱晃"。
     * 垂直向下 + 45°/75° 的宽锥已经足以覆盖整片地面，不需要任何横向分量。</p>
     *
     * <p>留这个常量（而不是直接删掉那一乘项）是为了让公式三支保持同构：
     * 想调回一点点扫描感只改这一个数，读代码的人也不用重新推导。</p>
     */
    static final double CEILING_RADIAL = 0.0D;

    private static final int BASE_RGB = 0x2E3338;
    private static final int RIM_RGB = 0x4A525A;

    @Override
    public void render(TileAlarmLight te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        if (te == null || te.getWorld() == null) {
            return;
        }
        IBlockState state = te.getWorld().getBlockState(te.getPos());
        if (!(state.getBlock() instanceof BlockAlarmLight)) {
            return;
        }
        EnumFacing facing = state.getValue(BlockAlarmLight.FACING);
        float angle = te.angleDeg(partialTicks);
        boolean on = state.getValue(BlockAlarmLight.ON).booleanValue();
        int rgb = te.config().color & 0xFFFFFF;

        // 正交基：axis = 灯口朝外；u/v 是垂直于轴的两个径向
        // 1.12.2 的 EnumFacing 是 getFrontOffsetX/Y/Z（getXOffset 是 1.13+ 的名字），
        // 直接用 getDirectionVec() 取 Vec3i 更省事
        Vec3i n = facing.getDirectionVec();
        Vec3d axis = new Vec3d(n.getX(), n.getY(), n.getZ());
        Vec3d u = perpendicular(axis);
        Vec3d v = axis.crossProduct(u);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        boolean cullWasOn = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        // 光照开关也要原样还回去：TESR 通常跑在 disableLighting 状态，
        // 无条件 enableLighting 会让后面渲染的东西突然变暗（或变亮）
        boolean lightingWasOn = GL11.glIsEnabled(GL11.GL_LIGHTING);

        GlStateManager.pushMatrix();
        GlStateManager.translate(x + 0.5D, y + 0.5D, z + 0.5D);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableCull();

        // ---------- 1) 底座（不透明，写深度） ----------
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        cylinder(buf, axis, u, v, -0.5D, -0.36D, 0.42D, 0.42D, BASE_RGB, 1.0F, true, false);
        cylinder(buf, axis, u, v, -0.38D, -0.34D, 0.45D, 0.45D, RIM_RGB, 1.0F, false, false);
        tess.draw();

        // v5.0：自发光（lightmap 240）整体移除，灯罩只吃环境光照，不再有任何白光体

        // ---------- 2a) 灯泡内胆（v4.9.5 恢复 / v4.9.6 加深）----------
        // 恢复的是"灯泡本体"，不是 v4.4.1 那个自发光亮核：
        //   · 颜色压暗（往 0x14181C 混 55%），不再是 mix(rgb, 白, 0.70) 那种白光团
        //   · alpha 提到 0.85，看起来是实体而不是一层粉色滤镜
        //   · 仍然不写深度 —— 否则反光板转到背面时会被整片挡掉，扫起来像卡帧
        GlStateManager.depthMask(false);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        int core = on ? mix(rgb, 0x14181C, 0.55F) : 0x33383D;
        cylinder(buf, axis, u, v, -0.34D, 0.04D, 0.22D, 0.19D, core, 0.85F, true, true);
        tess.draw();

        // ---------- 2) 灯芯：两块反光板绕轴旋转 ----------
        if (on) {
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            reflector(buf, axis, u, v, Math.toRadians(angle), rgb);
            reflector(buf, axis, u, v, Math.toRadians(angle + 180.0D), rgb);
            tess.draw();
        }

        // ---------- 3) 灯罩（半透明，不写深度，双面） ----------
        GlStateManager.depthMask(false);
        // v4.9.6：白色混比 0.45 → 0.28、alpha 0.22 → 0.16。
        // 之前"整个灯泡是一层粉光"就是这两个数太高：白混把红色冲成粉，alpha 太厚把内胆糊掉。
        float glassAlpha = on ? 0.16F : 0.10F;
        int glass = on ? mix(rgb, 0xFFFFFF, 0.28F) : 0xB8C2CC;
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        cylinder(buf, axis, u, v, -0.36D, 0.10D, 0.36D, 0.34D, glass, glassAlpha, true, false);
        // 顶盖：一圈略亮的边，让"圆"的轮廓在亮处也看得见
        cylinder(buf, axis, u, v, 0.10D, 0.125D, 0.34D, 0.30D, mix(glass, 0xFFFFFF, 0.35F),
                glassAlpha + 0.06F, true, false);
        tess.draw();

        // ---------- 4) 3D 体积光柱：v4.8 废弃，v4.10 连同 beamCuts/emitCone 一并删除 ----------
        // 真实警示灯在空气里看不到光束（没开光影体积雾时），光只出现在被打亮的表面上。

        GlStateManager.popMatrix();

        // 恢复
        // v5.0：不再改 lightmap，所以这里也不需要写回
        GlStateManager.depthMask(depthMask);
        if (cullWasOn) {
            GlStateManager.enableCull();
        }
        GlStateManager.disableBlend();
        if (lightingWasOn) {
            GlStateManager.enableLighting();
        }
        GlStateManager.enableTexture2D();
    }

    // ==================== 几何 ====================

    /** 与 axis 垂直的单位向量（挑一个和它不平行的参考轴叉乘） */
    static Vec3d perpendicular(Vec3d axis) {
        Vec3d ref = Math.abs(axis.y) < 0.9D ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d p = axis.crossProduct(ref);
        double len = p.lengthVector();
        return len < 1.0E-6D ? new Vec3d(1, 0, 0) : p.scale(1.0D / len);
    }

    /** 局部 (x, y, z) → 世界偏移；y 沿灯轴，x/z 是径向 */
    static Vec3d local(Vec3d axis, Vec3d u, Vec3d v, double lx, double ly, double lz) {
        return new Vec3d(
                axis.x * ly + u.x * lx + v.x * lz,
                axis.y * ly + u.y * lx + v.y * lz,
                axis.z * ly + u.z * lx + v.z * lz);
    }

    private static void vertex(BufferBuilder buf, Vec3d axis, Vec3d u, Vec3d v,
                               double lx, double ly, double lz, int rgb, float a) {
        Vec3d p = local(axis, u, v, lx, ly, lz);
        buf.pos(p.x, p.y, p.z)
                .color(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F,
                        (rgb & 0xFF) / 255.0F, a)
                .endVertex();
    }

    /** 圆台侧面（r0→r1），可选上下盖 */
    private static void cylinder(BufferBuilder buf, Vec3d axis, Vec3d u, Vec3d v,
                                 double y0, double y1, double r0, double r1,
                                 int rgb, float a, boolean capTop, boolean capBottom) {
        for (int i = 0; i < SIDES; i++) {
            double a0 = Math.PI * 2.0D * i / SIDES;
            double a1 = Math.PI * 2.0D * (i + 1) / SIDES;
            double x0 = Math.cos(a0);
            double z0 = Math.sin(a0);
            double x1 = Math.cos(a1);
            double z1 = Math.sin(a1);
            vertex(buf, axis, u, v, x0 * r0, y0, z0 * r0, rgb, a);
            vertex(buf, axis, u, v, x1 * r0, y0, z1 * r0, rgb, a);
            vertex(buf, axis, u, v, x1 * r1, y1, z1 * r1, rgb, a);
            vertex(buf, axis, u, v, x0 * r1, y1, z0 * r1, rgb, a);
        }
        if (capTop) {
            cap(buf, axis, u, v, y1, r1, rgb, a, true);
        }
        if (capBottom) {
            cap(buf, axis, u, v, y0, r0, rgb, a, false);
        }
    }

    /** 圆面（扇形拼） */
    private static void cap(BufferBuilder buf, Vec3d axis, Vec3d u, Vec3d v,
                            double y, double r, int rgb, float a, boolean up) {
        for (int i = 0; i < SIDES; i++) {
            double a0 = Math.PI * 2.0D * i / SIDES;
            double a1 = Math.PI * 2.0D * (i + 1) / SIDES;
            vertex(buf, axis, u, v, 0, y, 0, rgb, a);
            vertex(buf, axis, u, v, Math.cos(a0) * r, y, Math.sin(a0) * r, rgb, a);
            vertex(buf, axis, u, v, Math.cos(a1) * r, y, Math.sin(a1) * r, rgb, a);
            vertex(buf, axis, u, v, 0, y, 0, rgb, a);
        }
    }

    /**
     * 一块反光板：从轴心斜着立起来的小平板。
     * 两块对置（角度差 180°）就形成了旋转警示灯里那个"双面反光碗"的样子 ——
     * 转到正面时亮、转到侧面时几乎看不见，从而产生"一闪一闪扫过去"的观感。
     */
    private static void reflector(BufferBuilder buf, Vec3d axis, Vec3d u, Vec3d v,
                                  double rad, int rgb) {
        double cs = Math.cos(rad);
        double sn = Math.sin(rad);
        // 板在"径向 cs/sn"方向上，从半径 0.03 到 0.30，沿轴向 -0.10..0.06 倾斜
        double rIn = 0.03D;
        double rOut = 0.30D;
        double yLow = -0.16D;
        double yHigh = 0.02D;
        double w = 0.14D;   // 板宽（垂直于径向）
        // 四个角：内下、外下、外上、内上（径向 × 轴向）
        double[] rs = {rIn, rOut, rOut, rIn};
        double[] ys = {yLow, yLow + 0.10D, yHigh + 0.10D, yHigh};
        double px = 0;
        for (int i = 0; i < 4; i++) {
            double rr = rs[i];
            double yy = ys[i];
            // 局部坐标：径向 = (cs, 0, sn)，法向 = 垂直于径向的水平方向 (-sn, 0, cs)
            double cx = cs * rr;
            double cz = sn * rr;
            double side = (i % 2 == 0) ? -w : w;
            px = cx - sn * side;
            double pz = cz + cs * side;
            vertex(buf, axis, u, v, px, yy, pz, mix(rgb, 0xFFFFFF, 0.55F), 1.0F);
        }
    }

    /**
     * 径向平面里的单位朝向量：{@code u·cos(spin) + v·sin(spin)}。
     *
     * <p>{@code angleDeg} 就是灯芯转到的角度。立杆时 {@code u/v} 组成水平面，
     * 于是这一项把光束在<b>水平面</b>里扫 —— 真实旋转警示灯的样子。</p>
     */
    private static Vec3d radialUnit(Vec3d u, Vec3d v, double angleDeg) {
        double spin = Math.toRadians(angleDeg);
        return u.scale(Math.cos(spin)).add(v.scale(Math.sin(spin)));
    }

    /** 取一个向量的水平分量并单位化；退化时兜底 {@code (0,0,1)} */
    private static Vec3d horizontal(Vec3d v) {
        Vec3d flat = new Vec3d(v.x, 0.0D, v.z);
        double len = flat.lengthVector();
        if (len < 1.0E-6D) {
            return new Vec3d(0.0D, 0.0D, 1.0D);
        }
        return flat.scale(1.0D / len);
    }

    /** 光束起点（世界坐标）：灯体中心沿轴内缩一点点，光从灯罩侧面射出 */
    static Vec3d beamMouth(BlockPos pos, Vec3d axis) {
        return new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                .add(axis.scale(BEAM_ORIGIN_Y));
    }

    /**
     * 光束在世界系里的方向（已归一化）—— <b>屏幕空间光照的唯一方向真源</b>。
     *
     * <p>★★ 以前光斑贴片和光照各写一遍的时候，只要有一边改了 pitch 或压角，
     * 就会出现"光斑在左、光照在右"，而且因为两边看起来都"像是有光"、极难定位。
     * 现在整个 mod 只有这里算一次，{@code AlarmSpotlights} 直接拿来用。</p>
     *
     * <h3>v5.0.0：按 {@link LightType} 三分支（不再"先算通用式再局部打补丁"）</h3>
     * v4.x 的写法是"先用局部正交基算一个通用方向，挂壁时再特殊处理"，
     * 结果每次调参都在补丁之间互相踩。现在三种姿态各写一段，
     * 每段只有三五行，改一种不会波及另外两种：
     * <ul>
     *   <li><b>FLOOR</b>：径向扫描 + {@link #BEAM_PITCH} 抬离 + {@link #BEAM_DROP} 下压；</li>
     *   <li><b>CEILING</b>：<b>严格垂直向下</b>（{@code axis}），零横向扫描；</li>
     *   <li><b>WALL</b>：<b>单束完整贴墙圆环 + 下移</b> ——
     *       {@code normalize(T·(R·cos) + V·(yOffset + R·sin) − n·SEEP)}，
     *       半径 {@code 0.5}、圆心下移 {@code 1.0}、掠射角 {@code 0.2}。</li>
     * </ul>
     *
     * @param axis     灯口朝外的单位向量（{@code FACING}）
     * @param u        与 axis 垂直的径向基
     * @param v        {@code axis × u}，第二个径向基
     * @param angleDeg 灯芯当前转角（含 partialTicks 插值）
     */
    static Vec3d beamWorldDir(Vec3d axis, Vec3d u, Vec3d v, double angleDeg) {
        LightType type = LightType.classify(axis);
        Vec3d radial = radialUnit(u, v, angleDeg);
        switch (type) {
            case WALL: {
                // ★★★ v5.4.9：完整贴墙圆环 + 光斑中心下移 1 格。
                //   cos/sin 公式结构不动，只把圆心加 yOffset 下移，V = WALL_DOWN = (0,-1,0)。
                Vec3d n = horizontal(axis); // 墙面朝外（房间方向）
                Vec3d t = horizontal(u);    // 沿墙水平
                double ang = Math.toRadians(angleDeg);
                double radius = 0.5D;
                double yOffset = 1.0D;       // ★ 将光斑中心往下移动 1 格
                double WALL_NORMAL_SEEP = 0.2D;
                Vec3d targetOnWall = t.scale(radius * Math.cos(ang))
                        .add(WALL_DOWN.scale(yOffset + radius * Math.sin(ang)));
                return targetOnWall.subtract(n.scale(WALL_NORMAL_SEEP)).normalize();
            }
            case CEILING: {
                // axis 指向正下方 ⇒ 光束就是"垂直向下"，不吃 angleDeg、不吃 pitch、不吃 drop。
                // CEILING_RADIAL 必须是 0（见常量说明），公式保留径向项只为三支同构。
                return axis.add(radial.scale(CEILING_RADIAL)).normalize();
            }
            case FLOOR:
            default: {
                // 原 v4.x 的通用式等价形式：axis 分量 = sin(pitch)（负 = 朝下），
                // 径向分量 = cos(pitch)。写成这样比走 local() 更好读，结果完全一致。
                double pitch = Math.toRadians(BEAM_PITCH);
                Vec3d dir = radial.scale(Math.cos(pitch)).add(axis.scale(Math.sin(pitch)));
                return new Vec3d(dir.x, dir.y - BEAM_DROP, dir.z).normalize();
            }
        }
    }
    /** 两个颜色按 t 混合（t=0 全 a，t=1 全 b） */
    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
