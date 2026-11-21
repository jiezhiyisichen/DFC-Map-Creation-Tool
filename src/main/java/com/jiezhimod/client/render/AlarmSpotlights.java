package com.jiezhimod.client.render;

import com.jiezhimod.JiezhiMod;
import com.jiezhimod.block.BlockAlarmLight;
import com.jiezhimod.block.TileAlarmLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 屏幕空间聚光灯（SSS）的光源收集器：把世界里<b>真的在转、真的亮着</b>的
 * {@link TileAlarmLight} 变成着色器能用的 uniform 数组。
 *
 * <h3>为什么不是"每帧扫一遍世界找灯"</h3>
 * 每帧遍历 64 格内所有区块的方块实体表，在 HBM 这种整合包里是几千次 map 迭代 ——
 * 60fps 下就是每秒十几万次，纯属自杀。这里分两层：
 * <ul>
 *   <li><b>候选表</b>：每 {@link #RESCAN_FRAMES} 帧、或玩家移动超过 {@link #RESCAN_MOVE} 格、
 *       或换了世界，才重新扫一遍 9×9 个区块的 {@code getTileEntityMap()}，
 *       把 64 格内的警报灯缓存成 {@link #lamps}；</li>
 *   <li><b>每帧</b>：只遍历这个小缓存（通常 0~10 盏），读方块状态判开关、算转角。</li>
 * </ul>
 * 开关状态<b>故意放在每帧那层</b>判：放在缓存层的话，灯被关掉要等 40 帧才灭，
 * 玩家会觉得"关不掉的灯"；而每帧读几个 {@code getBlockState} 几乎不花钱。
 *
 * <h3>为什么是"最近 2 盏 × 1~2 条光束 ≤ 4 个光源"</h3>
 * 一盏真实的旋转警示灯里是<b>两块对置的反光板</b>，所以它同时射出两条相隔 180° 的光束
 * （见 {@link RenderAlarmLight#beamWorldDir}）：
 * <ul>
 *   <li>立杆 → 在<b>水平面</b>内对扫（地面两瓣），2 条；</li>
 *   <li>挂壁 → 在<b>墙面竖直平面</b>内对扫（墙上两瓣），2 条（v5.1.5 恢复）；</li>
 *   <li>吊装 → 两条光完全重合，只发 1 条（重复那条只叠亮度）。</li>
 * </ul>
 * 条数统一由 {@link LightType#beamCount()} 给出。着色器开了 4 个槽位，
 * 这里按"最近 2 盏灯"填满 —— <b>两盏挂壁灯正好 4 束，一格不剩</b>，
 * 第三盏灯会在 {@link #emit} 里被 {@code count < MAX_LIGHTS} 静默截掉。
 *
 * <h3>亮度为什么按颜色归一化</h3>
 * 测试期用的是 {@code (3.0, 2.7, 2.1)} 这种暖白。直接套到纯红/纯蓝上会偏亮或偏暗，
 * 因为人眼对三通道的敏感度差了近一个数量级。这里按<b>最大分量</b>归一化到
 * {@link #PEAK}：红 {@code 0xFF0000} 与白 {@code 0xFFFFFF} 的峰值都是 {@code PEAK}，
 * 只有色相不同 —— 换什么颜色的灯都不会突然刺眼或看不见。
 */
public final class AlarmSpotlights {

    /**
     * 着色器里 {@code uLightPos[N]} 的长度，两边必须一致。
     *
     * <h3>★ v5.2.0：4 → 8</h3>
     * 挂壁灯恢复 2 束之后，<b>两盏挂壁灯就把 4 个槽位吃满了</b>，第三盏会被
     * {@code count < MAX_LIGHTS} 静默截掉。8 个槽位 = 4 盏挂壁灯 / 8 盏立杆灯 /
     * 任意组合 8 条光束。桌面 GPU 的 {@code MAX_FRAGMENT_UNIFORM_VECTORS} 都远大于
     * 8×5=40 个 vec4，代价只是片元着色器多跑 4 次空循环（权重 0，结果不变）。
     *
     * <p>★ 改这个数必须同步三处：本常量、{@link DepthDebugPass} 的 {@code uniform ... [N]}
     * 声明、以及那个 {@code for (int i = 0; i < N; i++)} 循环上限。
     * Java 侧的 {@code pos/dir/rgb/cone/range/plane} 数组都由本常量算长度，会自动跟随。</p>
     */
    public static final int MAX_LIGHTS = 8;

    /** 搜索半径（格） */
    private static final double SCAN_RADIUS = 64.0D;
    private static final double SCAN_RADIUS_SQ = SCAN_RADIUS * SCAN_RADIUS;
    /** 候选表重扫间隔（帧）；40 帧 ≈ 0.66 秒 */
    private static final int RESCAN_FRAMES = 40;
    /** 玩家移动超过这个距离就提前重扫（走路时不至于 0.6 秒才发现新灯） */
    private static final double RESCAN_MOVE = 2.0D;

    /**
     * 归一化后的峰值亮度。
     *
     * <p>v4.20 从 2.0 降到 1.8：着色器现在按表面法线做 N·L 衰减，掠射面本来就会变暗，
     * 再叠 2.0 会让正对光的那一小片糊成纯色块、看不出墙面纹理。</p>
     */
    private static final float PEAK = 1.8F;

    /**
     * 挂壁灯是否参与屏幕空间光照。
     *
     * <p>★ 留这个开关是<b>兜底</b>：如果挂壁灯怎么调都还是"怪光"，
     * 把这里改成 {@code false} 就彻底不给它传光源 —— 灯体模型、反光板、
     * 屏幕 UI 全都照旧，只是不再有那束光。改一行即可，不用动其它代码。</p>
     */
    private static final boolean WALL_MOUNT_SSS = true;

    /**
     * 近距命中阈值（格）：锥轴射出去不到这个距离就撞到方块时，走"近距特殊处理"。
     *
     * <h3>★★ 为什么近距命中不能生成裁剪平面</h3>
     * 屏幕空间聚光灯没有真的阴影，遮挡只能靠半空间平面近似：沿锥轴打一条射线，
     * 命中面的法线定义一张"墙后的像素不算光"的平面。但这个近似只在<b>中远距离</b>成立：
     * 一条射线只代表锥轴那一根线，它撞到脚边的方块不等于整个锥体被挡，
     * 而半空间是无限的 ⇒ 整片光斑被一刀切成方块（v4.22 截图里那块生硬的红方块）。
     *
     * <h3>★★★ v5.1.2：近距命中分类型处理（"压缩射程"已被证明是错的方向）</h3>
     * v5.0.0 曾经"近距命中就把这束光本帧关掉"，实机反馈是挂壁灯直接<b>隐身</b>；
     * v5.0.1~5.1.1 改成"把射程压到 {@code hitDist + PAD}"，实机反馈是<b>光变成贴在墙上的贴纸</b>。
     * 现在按类型分开，而且<b>两种情况都不再压缩射程</b>：
     * <ul>
     *   <li><b>{@link LightType#WALL}</b>：不裁剪平面，也不改射程 —— 而是生成一张
     *       垂直于光束的<b>端盖平面</b>（见 {@link #WALL_CAP_MIN}），光在墙面前截断、
     *       光锥仍是 15°/35° 的正常形状。</li>
     *   <li><b>{@link LightType#FLOOR} / {@link LightType#CEILING}</b>：近距命中多半是
     *       灯自己的底座、脚边的台阶或旁边的箱子 —— <b>直接忽略这次命中</b>，
     *       当作没打到东西，按原本的 {@link LightType#range()} 正常发光。</li>
     * </ul>
     *
     * <h3>★★★ v5.5.4：0 → 1.0（不是 2.0，有推导）</h3>
     * 5.5.0 排查时临时置 0，现在恢复过滤。但<b>不能恢复到 2.0</b>：
     * 挂壁灯（radius=1.0、深度 1.0）的锥轴与墙面法线恒成 45°，墙面命中距离恒为
     * {@code √(1²+1²) = 1.414 < 2.0} ⇒ 每一帧都走<b>端盖平面</b>分支，端盖零点
     * {@code max(1.414, 1.5)+0.02 = 1.52}。端盖垂直于光束，而墙面点沿光束的距离
     * {@code = 0.707·(1+y)}（y = 沿光束倾斜方向的墙而横向偏移）⇒ <b>y &gt; 1.15 的墙点全被切</b>
     * —— 内核（10°）本来就只到 y=1.43，切完正好又是一弯月牙。端盖平面是为
     * "灯塞凹槽"设计的兜底，不是给贴墙灯的常态裁剪。
     *
     * <p>1.0 的语义：命中距离 &lt; 1.0 的才算"贴脸垃圾命中"（灯座、凹槽邻块）走特殊分支；
     * 墙面本体（1.414）走通用的「竖直面 → 裁剪平面」路径，裁剪面就是墙面本身，
     * 光斑形状不受裁剪影响。</p>
     */
    private static final double MIN_CLEAR_DIST = 1.0D;

    /**
     * 近距命中时，<b>端盖平面</b>至少推到灯口外多远（格）。见 {@link #trace} 的说明。
     *
     * <h3>★★★ v5.1.2：射程不再被压缩，改用"端盖平面"（这是"光变成贴纸"的真根因）</h3>
     * 5.0.1~5.1.1 的做法是"近距命中就把 {@code range} 砍到 {@code hitDist + PAD}"。
     * 实机日志证明这条路走不通：{@code range} 在着色器里<b>不只是一个截断距离</b>，
     * 它还决定渐隐曲线的位置 ——
     * <pre>atten *= 1 - smoothstep(rmax*0.45, rmax, dist)</pre>
     * 一旦把 rmax 压到 1.8 格，整束光就被钉在灯口 1 格内：光斑只剩一个
     * 直径约 1.4 格的亮盘，边缘是 smoothstep 的硬边，看起来像<b>贴在墙上的发光贴纸</b>，
     * 而不是"这面墙被灯照亮了"。玩家看到的就是"光打在前方一格"。
     *
     * <p>正确做法是把两件事拆开：<b>射程保持 {@link LightType#range()}</b>（6 格，渐隐曲线正常），
     * <b>遮挡用一张垂直于光束的"端盖"平面</b>截断 —— 平面法线 = 光束方向，
     * 平面外侧（墙后）判为背面、不参与光照。这样光锥仍按 15°/35° 自然铺开，
     * 打到前方的墙面上是一片边缘柔和的光斑，而且<b>一像素都不会渗到墙后</b>。</p>
     *
     * <p>{@code 1.5} 是端盖的<b>下限</b>：灯被塞进一格深的凹槽、或紧贴着一个凸出方块时，
     * 命中距离可能只有 0.02~0.5 格，端盖跟着贴到灯口就等于"整盏灯不发光"
     * （v5.0.0 踩过这个坑）。推到 1.5 格后灯至少能照亮身边一圈，
     * 而一格厚的墙背后（{@code hitDist + 2} 起）依然在端盖之外，不会渗光。</p>
     */
    private static final double WALL_CAP_MIN = 1.5D;

    /**
     * 端盖平面沿光束方向外推的距离（格）。
     *
     * <p>着色器里 {@code cut = dot(n, P) - plane.w}、{@code vis = smoothstep(-0.35, 0, cut)}，
     * 所以平面零点上的像素本来是半亮。外推 0.02 格让"被照亮的那个面"整体落在
     * {@code cut < 0} 一侧 ⇒ 满亮；再往外 0.35 格才渐隐到全黑。</p>
     */
    private static final double CAP_PUSH = 0.02D;

    /**
     * 裁剪平面沿法线<b>向外</b>推的距离（格）。
     *
     * <p>平面死死卡在方块表面上时，光斑贴着墙根就是一刀切的硬边。
     * 外推 0.1 格让墙面本身落在 {@code cut > 0} 一侧（不裁），
     * 墙后 0.1 格起才开始挡；再配合着色器里的软过渡带，
     * 边界就变成渐变而不是锯齿。</p>
     */
    private static final double PLANE_PUSH = 0.10D;

    // ---- 输出（复用同一组数组，绝不每帧 new）----
    /** xyz × {@link #MAX_LIGHTS} */
    public final float[] pos = new float[MAX_LIGHTS * 3];
    /** 已归一化的方向 × {@link #MAX_LIGHTS} */
    public final float[] dir = new float[MAX_LIGHTS * 3];
    /** rgb（已含归一化增益）× {@link #MAX_LIGHTS} */
    public final float[] rgb = new float[MAX_LIGHTS * 3];
    /** cosInner, cosOuter × {@link #MAX_LIGHTS} */
    public final float[] cone = new float[MAX_LIGHTS * 2];
    /** 射程 × {@link #MAX_LIGHTS} */
    public final float[] range = new float[MAX_LIGHTS];
    /**
     * 遮挡平面 × {@link #MAX_LIGHTS} = {@code vec4(nx, ny, nz, d)}，
     * 平面方程 {@code dot(n, P) - d}。<b>全 0 = 不裁</b>（射线没打到东西），
     * 因为 {@code dot(0, P) - 0 == 0} 永远不会小于判定阈值 —— 自失效，不用额外开关。
     */
    public final float[] plane = new float[MAX_LIGHTS * 4];
    /** 本帧有效光源数（0 = 附近没有开着的警报灯） */
    public int count;

    /** 候选灯缓存（只在重扫时改动） */
    private final List<TileAlarmLight> lamps = new ArrayList<TileAlarmLight>();
    private final List<TileAlarmLight> scratch = new ArrayList<TileAlarmLight>();

    private World scannedWorld;
    private int rescanTimer;
    private double scanX;
    private double scanY;
    private double scanZ;

    /** 日志节流：只在"有没有灯 / 是哪盏"变化时打一行 */
    private String lastReport = "";
    /**
     * 每帧调一次：刷新候选表（按需）并填充本帧的光源数组。
     *
     * @param partialTicks 帧插值，转角必须用它，否则 20Hz 的转速看起来一顿一顿
     */
    public void update(World world, double camX, double camY, double camZ, float partialTicks) {
        this.count = 0;
        if (world == null) {
            this.lamps.clear();
            this.scannedWorld = null;
            return;
        }
        maybeRescan(world, camX, camY, camZ);

        // ---- 每帧：过掉关着 / 已失效 / 不再是本世界的灯，取最近两盏 ----
        TileAlarmLight bestA = null;
        TileAlarmLight bestB = null;
        double distA = Double.MAX_VALUE;
        double distB = Double.MAX_VALUE;
        for (int i = 0; i < this.lamps.size(); i++) {
            TileAlarmLight te = this.lamps.get(i);
            if (te == null || te.isInvalid() || te.getWorld() != world) {
                continue;
            }
            BlockPos p = te.getPos();
            IBlockState state = world.getBlockState(p);
            if (!(state.getBlock() instanceof BlockAlarmLight)) {
                continue;
            }
            if (!state.getValue(BlockAlarmLight.ON).booleanValue()) {
                continue;
            }
            if (te.config().color == 0) {
                // 全黑 = 没有可见光，别浪费一个槽位
                continue;
            }
            double d = p.distanceSq(camX, camY, camZ);
            if (d < distA) {
                distB = distA;
                bestB = bestA;
                distA = d;
                bestA = te;
            } else if (d < distB) {
                distB = d;
                bestB = te;
            }
        }

        if (bestA != null) {
            emit(bestA, world, partialTicks);
        }
        if (bestB != null) {
            emit(bestB, world, partialTicks);
        }
        report(bestA, world);
    }

    // ------------------------------------------------------------------
    // 候选表
    // ------------------------------------------------------------------

    private void maybeRescan(World world, double camX, double camY, double camZ) {
        boolean moved = (camX - this.scanX) * (camX - this.scanX)
                + (camZ - this.scanZ) * (camZ - this.scanZ) > RESCAN_MOVE * RESCAN_MOVE;
        if (world == this.scannedWorld && !moved && --this.rescanTimer > 0) {
            return;
        }
        rescan(world, camX, camY, camZ);
    }

    /**
     * 重扫 64 格内的区块，缓存所有 {@link TileAlarmLight}。
     *
     * <p>用 {@code Chunk#getTileEntityMap()} 而不是 {@code world.loadedTileEntityList}：
     * 后者是整个维度所有已加载的方块实体（HBM 整合包里轻松上万），
     * 而我们只关心 9×9 区块里的那一点点。</p>
     */
    private void rescan(World world, double camX, double camY, double camZ) {
        this.scannedWorld = world;
        this.rescanTimer = RESCAN_FRAMES;
        this.scanX = camX;
        this.scanY = camY;
        this.scanZ = camZ;

        this.scratch.clear();
        int chunkRadius = (int) Math.ceil(SCAN_RADIUS / 16.0D);
        int centerX = floor(camX) >> 4;
        int centerZ = floor(camZ) >> 4;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                Chunk chunk;
                try {
                    chunk = world.getChunkFromChunkCoords(cx, cz);
                } catch (Throwable ignored) {
                    // 区块正好在卸载 / 维度切换的瞬间，跳过这一块
                    continue;
                }
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                for (TileEntity te : chunk.getTileEntityMap().values()) {
                    if (!(te instanceof TileAlarmLight) || te.isInvalid()) {
                        continue;
                    }
                    double d = te.getPos().distanceSq(camX, camY, camZ);
                    if (d <= SCAN_RADIUS_SQ) {
                        this.scratch.add((TileAlarmLight) te);
                    }
                }
            }
        }
        this.lamps.clear();
        this.lamps.addAll(this.scratch);

        // 稳定排序只是为了让"最近两盏"的并列情况在不同帧里选到同一盏，
        // 否则两盏等距的灯会每帧互抢槽位，光斑跟着抖。
        Collections.sort(this.lamps, new Comparator<TileAlarmLight>() {
            @Override
            public int compare(TileAlarmLight a, TileAlarmLight b) {
                return a.getPos().compareTo(b.getPos());
            }
        });
    }

    // ------------------------------------------------------------------
    // 单盏灯 → 1~2 条光束（条数由 LightType 决定）
    // ------------------------------------------------------------------

    private void emit(TileAlarmLight te, World world, float partialTicks) {
        BlockPos p = te.getPos();
        IBlockState state = world.getBlockState(p);
        if (!(state.getBlock() instanceof BlockAlarmLight)) {
            return;
        }
        // 正交基与 TESR 完全同源：axis = 灯口朝外，u/v 是径向
        net.minecraft.util.math.Vec3i n = state.getValue(BlockAlarmLight.FACING).getDirectionVec();
        Vec3d axis = new Vec3d(n.getX(), n.getY(), n.getZ());
        Vec3d u = RenderAlarmLight.perpendicular(axis);
        Vec3d v = axis.crossProduct(u);

        Vec3d mouth = RenderAlarmLight.beamMouth(p, axis);

        int argb = te.config().color & 0xFFFFFF;
        // 蓝色分量改名 blue：下面光束循环要用 b 作循环变量（与用户的命名约定一致）
        float r = ((argb >> 16) & 0xFF) / 255.0F;
        float g = ((argb >> 8) & 0xFF) / 255.0F;
        float blue = (argb & 0xFF) / 255.0F;
        float peak = Math.max(r, Math.max(g, blue));
        float gain = peak < 0.02F ? 0.0F : PEAK / peak;

        float angle = te.angleDeg(partialTicks);
        // ★ v5.2.2：挂壁灯的方向重新旋转了，动态感由旋转本身提供，
        //   不再需要 v5.2.1 的"亮度呼吸"补偿（那会让旋转的光斑忽明忽暗）。
        // ★ v5.0.0：安装方式统一由 LightType 判定（axis.y > 0.5 立杆 / < -0.5 吊装 / 其余挂壁），
        //   光束方向由 RenderAlarmLight.beamWorldDir 按同一个枚举分三支算。
        //   这里只关心"发几条"——条数也是枚举的一部分，不再手写 1/2。
        LightType type = LightType.classify(axis);
        if (type.wallMounted() && !WALL_MOUNT_SSS) {
            return;                      // 兜底开关：挂壁灯完全不传光源
        }
        final int beams = type.beamCount();
        // ★ 光锥与射程按类型取（v5.0.0 起分档）：
        //   立杆 35°/55°·8格、挂壁 45°/75°·10格、吊顶 45°/75°·10格。
        //   三种姿态共用一组锥角就是"挂壁灯变成大喇叭罩住整面墙"的原因。
        final float cosInner = type.cosInner();
        final float cosOuter = type.cosOuter();
        final float range = type.range();
        for (int b = 0; b < beams && this.count < MAX_LIGHTS; b++) {
            // ★ 第 2 条 = 同一盏灯转 180°：两块对置反光板的真实物理行为。
            //   方向、射线、遮挡平面全部<b>各自独立</b>算一遍（下面 trace 用各自的 slot），
            //   所以一束撞墙不会连累另一束。
            Vec3d d = RenderAlarmLight.beamWorldDir(axis, u, v, angle + 180.0D * b);
            int slot = this.count;
            // ★ 打射线：会顺手把这个槽位的 range 与遮挡平面写好
            //   （近距命中时挂壁灯生成"端盖"平面，射程<b>不动</b>）。
            //   注意 trace 是在写 pos/dir/rgb **之前**调的，因为它可能改写 this.range[slot]。
            trace(world, mouth, d, p, slot, range, type);
            int s = slot * 3;
            this.pos[s] = (float) mouth.x;
            this.pos[s + 1] = (float) mouth.y;
            this.pos[s + 2] = (float) mouth.z;
            this.dir[s] = (float) d.x;
            this.dir[s + 1] = (float) d.y;
            this.dir[s + 2] = (float) d.z;
            this.rgb[s] = r * gain;
            this.rgb[s + 1] = g * gain;
            this.rgb[s + 2] = blue * gain;
            this.cone[slot * 2] = cosInner;
            this.cone[slot * 2 + 1] = cosOuter;
            this.count++;
        }
    }

    /**
     * 沿光束打一条体素射线，用命中结果生成<b>遮挡半空间</b>（<b>不改射程</b>）。
     *
     * <h3>这是在补什么</h3>
     * 屏幕空间聚光灯只认深度、不认遮挡 —— 光会穿墙照到另一侧。没有 Shadow Map 的前提下，
     * 用一次射线把"锥轴撞到的那个面"变成一张半空间平面，就能挡住墙后那一片。
     *
     * <h3>★★ 射程绝不参与遮挡（v4.16 的教训）</h3>
     * v4.15.0 曾经在命中方块后把这条光的射程砍到 {@code 命中距离 + 2}，结果
     * <b>地面上的整片光斑消失</b>。原因是几何上的：一个圆锥只有<b>一条</b>射程，
     * 而射线只代表锥轴那一根线 —— 锥轴撞到墙，不等于整个圆锥都被挡住。
     * 光该照到的地面往往就分布在那面墙的前方、锥轴命中点之外。
     *
     * <p>所以现在：<b>射程按 {@link LightType} 分档固定（不随命中距离变化），遮挡只由半空间平面负责</b>。
     * 命中面的法线定义了一个平面，平面<b>背面</b>（墙后）的像素不参与这条光的计算，
     * 而平面这一侧（墙前的地面、被照亮的墙面本身）照常。代价只有着色器里一个 dot。</p>
     *
     * <p><b>只有满方块的竖直面才生成平面</b>（见下文的两次教训，别放宽）。
     * 非满方块（栅栏、火把）拿法线做半空间会在世界里切出一片莫名其妙的黑影，
     * 这类直接退化成"没有平面"，光自然铺开。</p>
     *
     * <p>v4.16 曾只对竖直面生成平面，结果"光从墙上方掠过、打中墙顶"时墙后整片
     * 草地照样被照亮 —— 因为顶面被那一行 {@code n.y == 0} 过滤掉了。
     * 水平面并不会切坏地面光斑：光斑本身就在那张平面上，{@code dot} 恒为 0。</p>
     *
     * <p>射线起点在灯体中心（{@code beamMouth}），也就是<b>在灯自己那个方块里面</b>，
     * 所以第一次命中几乎一定是灯自己：跳过自身方块 / 0.5 格内的贴脸命中，
     * 往前挪 0.05 格重打，最多 6 次。</p>
     *
     * <h3>★★★ v5.1.2：命中距离 &lt; {@link #MIN_CLEAR_DIST} 格 → 分类型处理</h3>
     * 见 {@link #MIN_CLEAR_DIST} 与 {@link #WALL_CAP_MIN} 的完整说明。一句话：
     * 挂壁灯生成一张垂直于光束的<b>端盖平面</b>（射程不动，仍是 8 格），
     * 立杆 / 吊顶灯直接忽略这次命中（多半是灯座或脚边方块）。
     * <b>既不压缩射程，也不把这束光整束关掉</b> —— 前者让光变成"贴在墙上的亮盘"，
     * 后者让灯直接隐身，两条都实机验证过。
     *
     * @param range 这一束光的射程（按 {@link LightType} 分档），会写进 {@code uLightColorRange.w}
     * @param type  安装方式，近距命中时决定"端盖截断"还是"忽略"
     */
    private void trace(World world, Vec3d mouth, Vec3d dir, BlockPos selfPos,
                       int slot, float range, LightType type) {
        int base = slot * 4;
        this.plane[base] = 0.0F;
        this.plane[base + 1] = 0.0F;
        this.plane[base + 2] = 0.0F;
        this.plane[base + 3] = 0.0F;
        this.range[slot] = range;

        // ★★★ v5.5.6：挂壁灯彻底不打射线、不生成任何裁剪平面。
        //
        //   光斑本来就贴在墙上，Z-Buffer 深度测试已经保证"墙挡住的部分画不出来"，
        //   半空间平面是多此一举 —— 而且实测有害：灯口附近墙体凹凸不平（墙沿、
        //   邻块侧脸、墙顶），锥轴频繁命中 side=up/south/west 的方块，裁剪平面
        //   帧帧变脸 ⇒ 光斑被切碎 + 疯狂闪烁（23:16 的日志：同一秒内命中面在
        //   north/west/south/up 之间来回跳）。
        //   plane 保持全 0（length==0）⇒ 着色器判"无遮挡平面"，光自然铺开。
        //   ★ 代价：光会按锥角穿过墙体照到墙后 —— 这是用户明确接受的取舍。
        if (type == LightType.WALL) {
            return;
        }

        Vec3d end = mouth.add(dir.scale(range));
        // 起点再挤出 0.02：灯口正好落在两格交界面上，贴着面发射偶尔会命中邻格
        Vec3d from = mouth.add(dir.scale(0.02D));
        for (int guard = 0; guard < 6; guard++) {
            RayTraceResult r = world.rayTraceBlocks(from, end, false, true, false);
            if (r == null || r.typeOfHit != RayTraceResult.Type.BLOCK || r.sideHit == null) {
                return;                      // 一路打空：没有平面，光自然铺开
            }
            IBlockState st = world.getBlockState(r.getBlockPos());
            boolean solid = st.getBlock().getCollisionBoundingBox(st, world, r.getBlockPos())
                    != net.minecraft.block.Block.NULL_AABB;
            boolean self = selfPos != null && selfPos.equals(r.getBlockPos());
            boolean tooClose = from.distanceTo(r.hitVec) < 0.5D;
            if (solid && !self && !tooClose) {
                double hitDist = mouth.distanceTo(r.hitVec);
                if (hitDist < MIN_CLEAR_DIST) {
                    if (type == LightType.WALL) {
                        // ★★★ v5.1.2：**端盖平面**（不是压缩射程）。
                        //
                        //   射程保持 6 格 ⇒ 渐隐曲线正常 ⇒ 光锥按 15°/35° 自然铺开；
                        //   遮挡交给一张垂直于光束的平面：法线 = 光束方向，
                        //   零点在"灯口 + cap"处，平面外侧（墙后）一律判为背面。
                        //
                        //   ★ 5.0.1~5.1.1 在这里写的是 this.range[slot] = hitDist + PAD，
                        //     结果整束光被钉在灯口 1 格内 ⇒ 墙上只剩一个硬边亮盘
                        //     （实机 5.1.1 日志：range=1.87，玩家看到"光打在前方一格"）。
                        double cap = Math.max(hitDist, WALL_CAP_MIN) + CAP_PUSH;
                        this.plane[base] = (float) dir.x;
                        this.plane[base + 1] = (float) dir.y;
                        this.plane[base + 2] = (float) dir.z;
                        // plane.w = dot(dir, 灯口) + cap：dir 已归一化，所以这就是"沿光束的距离"
                        this.plane[base + 3] = (float) (dir.x * mouth.x + dir.y * mouth.y
                                + dir.z * mouth.z + cap);
                    }
                    // 立杆 / 吊顶：忽略这次命中，按原本射程正常发光
                    return;
                }
                // 注意：这里**不改 range**。命中只用来生成遮挡平面。
                Vec3i n = r.sideHit.getDirectionVec();
                // ★★★ v4.21：**只有竖直面**（n.y == 0）才允许生成裁剪平面。
                //
                //   这一条曾经被我删掉过（v4.17 为了修"光从墙顶掠过、墙后草地全亮"），
                //   结果造成了更严重的灾难：射线打中地面（n=(0,1,0)）会生成一张
                //   <b>无限大的水平平面</b>，把整片地面判成"背面" —— 表现就是
                //   墙上光斑漂亮、地上一个像素都不亮。地面是屏幕空间光照最主要的
                //   受体，宁可留一点"光从墙顶漏过去"的瑕疵，也不能让地面瞎掉。
                //
                //   水平面之所以危险：地面像素的 world.y 与平面 d 几乎相等，
                //   只要 d 稍微偏（命中点在墙顶、台阶顶），cut 立刻变成大负数，
                //   smoothstep 直接归零。竖直墙面的平面则只影响墙后，不会波及地面。
                if (st.isFullCube() && n.getY() == 0) {
                    this.plane[base] = n.getX();
                    this.plane[base + 1] = n.getY();
                    this.plane[base + 2] = n.getZ();
                    // d = dot(n, 命中点) + PLANE_PUSH：把平面沿法线往外推 0.1 格，
                    // 让"零面"落在方块表面之外一点，避免贴面硬切。
                    this.plane[base + 3] = (float) (n.getX() * r.hitVec.x
                            + n.getY() * r.hitVec.y + n.getZ() * r.hitVec.z
                            + PLANE_PUSH);
                }
                return;
            }
            from = r.hitVec.add(dir.scale(0.05D));
            if (from.distanceTo(end) < 0.10D) {
                return;
            }
        }
    }

    /** 只在"最近那盏灯"变了时打一行，方便从日志判断到底找没找到灯 */
    private void report(TileAlarmLight nearest, World world) {
        String now = nearest == null
                ? "0"
                : this.lamps.size() + "@" + nearest.getPos().toString();
        if (now.equals(this.lastReport)) {
            return;
        }
        this.lastReport = now;
        if (nearest == null) {
            JiezhiMod.warn("[SSS] 64 格内没有开启的警报灯，本帧不画光"
                    + "（开着的灯才会被收集，检查一下 ON 状态 / 颜色是不是黑的）。");
        }
        // ★ v5.5.7：删除"候选 N 盏 + 光束0 mouth/dir"排查日志。
        //   两盏灯轮流成为"最近"时 lastReport 节流失效，每几秒刷一行（v5.5.6 实机）。
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
