package com.jiezhimod.client.render;

import com.jiezhimod.JiezhiMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;

import java.nio.FloatBuffer;

/**
 * 屏幕空间聚光灯（SSS）的验证程序，目前停在<b>阶段 2：聚光灯</b>。
 *
 * <p>整条链路：{@link DepthCapture} 把主 FBO 的深度和颜色整屏拷进自己的纹理 →
 * 本类用一个全屏四边形，对每个像素反投影出世界坐标 → 与测试光源做聚光灯着色 →
 * 写回屏幕。三档可视化由着色器里的 {@code MODE} 常量切换
 * （0 线性深度 / 1 世界坐标彩带 / 2 聚光灯）。</p>
 *
 * <h3>零矩阵顶点着色器</h3>
 * 顶点直接写 {@code gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0)}，
 * 传进去的四个点就是 NDC 的 (-1,-1)~(1,1)。既然 shader 覆盖了变换，
 * modelview / projection 怎么摆都不影响结果 —— 所以这里既不 push 矩阵、
 * 也不碰 matrixMode，少一处能泄漏的状态。
 *
 * <h3>为什么用 {@code #version 120}</h3>
 * 1.12.2 的上下文是 compatibility profile，GLSL 120 才能用 {@code gl_Vertex}
 * 这种内建属性接原版 Tessellator 的顶点数组；写 {@code #version 330 core}
 * 会要求 VAO + 顶点属性指针，那是本项目明令禁止的现代管线。
 *
 * <h3>为什么在 RenderWorldLastEvent</h3>
 * 世界刚画完、深度还没被清掉，是唯一能读到"整帧世界深度"的时刻。
 * 放到 HUD 阶段读，读到的是 GUI 画过之后的深度，基本没用。
 */
public class DepthDebugPass {

    /**
     * 总开关：<b>v5.1.1 起默认开启</b>。
     *
     * <h3>★ 为什么改成默认开</h3>
     * 5.0 之后 {@code MODE = 2}，这个 pass 画的是<b>真正的聚光灯</b>，不是早期那张黑白深度灰度图，
     * 没有"会把调试画面带给玩家"的问题了。而默认关的代价是：只要启动参数没传进 JVM
     * （换启动器、手滑删掉那一行、参数填到"游戏参数"而不是"JVM 参数"里），
     * 整个 pass 就不注册 —— 表现为<b>画面彻底没有光，日志里连一个 {@code [SSS]} 前缀都没有</b>，
     * 完全看不出是"功能没开"还是"功能坏了"。5.1.0 就是栽在这上面。
     *
     * <p>要关就加 {@code -Djiezhimod.debugDepth=false}。</p>
     */
    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("jiezhimod.debugDepth", "true"));

    /**
     * 由 {@code ClientProxy.init()} 调用：<b>无论开关如何都先打一行状态</b>，再按需注册。
     *
     * <p>这行日志是排障的分水岭 —— 有它才能一眼分清"没注册"和"注册了但没画出来"。
     * 以前静默不注册，玩家只能看到"没有光"，没有任何线索。</p>
     */
    public static void registerIfEnabled() {
        if (ENABLED) {
            JiezhiMod.warn("[SSS] 引擎默认开启（屏幕空间聚光灯已注册；"
                    + "要关掉请加 -Djiezhimod.debugDepth=false）");
            MinecraftForge.EVENT_BUS.register(new DepthDebugPass());
        } else {
            JiezhiMod.warn("[SSS] 引擎被显式关闭（-Djiezhimod.debugDepth=false），"
                    + "本局不渲染聚光灯 —— 画面里不会出现任何警报灯的光");
        }
    }

    /** 光影探测结果缓存；null = 还没探过 */
    private static Boolean shaderPackLoaded;

    private static final String VERTEX_SRC =
            "#version 120\n"
            + "void main() {\n"
            + "    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n"
            + "}\n";

    /**
     * 取样坐标用 {@code gl_FragCoord.xy / uScreen} 现算，不传纹理坐标：
     * 深度纹理与窗口坐标同为「左下角原点」，两者天然对齐，不需要翻 V。
     *
     * <h3>为什么不能直接输出 d</h3>
     * 透视投影的窗口深度 {@code d} 与距离 {@code z} 的关系是
     * <pre>  d = f * (1 - n/z) / (f - n)  </pre>
     * {@code n = 0.05}、{@code f = 256} 时，1 格处 d 已经是 0.950，10 格处 0.995，
     * 50 格处 0.999 —— 整幅画面被压在 0.95~1.0 这 5% 的区间里，取 8 位灰度就是一片纯白。
     * 所以必须做一次「去非线性」。
     *
     * <h3>不用传 near/far 的做法</h3>
     * 把上式反解出 {@code 1 - d = n(f/z - 1)/(f - n)}，在 {@code z << f} 时
     * 近似 {@code 1 - d ≈ n/z}，于是
     * <pre>  z ≈ n / (1 - d)  </pre>
     * 只有 {@code n} 参与，而 1.12.2 的 {@code n} 是硬编码常量：
     * 对着 {@code EntityRenderer} 的字节码数过，是
     * {@code Project.gluPerspective(fov, aspect, 0.05f, farPlaneDistance)}。
     * 所以这一步<b>不需要新增 uniform，也不用动 Java</b>。
     * 远平面 {@code f} 只影响「远处偏差有多大」，对可视化没有决定性影响。
     *
     * <h3>为什么再套一个 log</h3>
     * 线性化之后的 z 是 1~256 格，直接除 256 会变成「近处全黑」。
     * {@code log(1+z)/log(1+FAR_RANGE)} 把近场拉开、远场压扁，
     * 这才是深度调试图该有的观感（1 格≈0.13、10 格≈0.43、100 格≈0.83）。
     *
     * <p>★ GLSL 源码<b>保持纯 ASCII</b>：部分老 Intel 驱动遇到 UTF-8 中文注释
     * 会直接编译失败，而那段失败日志通常没人会去看。</p>
     *
     * <h3>阶段 1：用逆投影 + 逆视图反推世界坐标</h3>
     * 标准反投影算法，一行都不能省：
     * <pre>
     *   屏幕 uv            -> NDC xy：         ndc.xy = uv * 2 - 1
     *   窗口深度 d (0~1)   -> NDC z：          ndc.z  = d  * 2 - 1
     *   NDC + w=1          -> 裁剪空间
     *   乘 逆投影           -> 相机空间（w 归一化后才是真正的 xyz）
     *   乘 逆视图           -> 世界空间（MC 的 modelview 已经烘焙了 -camera，所以是绝对坐标）
     * </pre>
     * {@code MODE} 是可视化开关：0 = 线性灰度（阶段 0 那版，留着 A/B）、
     * 1 = 世界坐标彩带（16 格一段）、2 = <b>聚光灯（默认）</b>。
     *
     * <p><b>为什么 1 用 0.0625 而不是 0.01</b>：x 是 -1872 这种量级，
     * {@code fract(x*0.01)} 一段跨 100 格，屏幕上只剩两三道极宽的色块，
     * 且绝对坐标值越大，浮点尾数越不够用 → 表现为那种"摩尔纹般的破碎"。
     * 16 格一段既贴得住方块，又能在视野里数得清。</p>
     *
     * <h3>阶段 3：多光源</h3>
     * 光照本体没变，仍是标准那套：
     * <pre>
     *   toLight = lightPos - world          // 表面指向光源
     *   theta   = dot(-normalize(toLight), lightDir)   // 1 = 正对光锥轴
     *   spot    = smoothstep(cosOuter, cosInner, theta)
     *   atten   = 1 / (1 + 0.1*d + 0.01*d*d)，再按射程削尾
     *   color   = scene + Σ(lightColor * atten * spot)
     * </pre>
     * 光源不再写死，而是由 {@link AlarmSpotlights} 每帧挑出最近的真实警报灯。
     * 数组长度 8（v5.2.0 起）必须与 {@link AlarmSpotlights#MAX_LIGHTS} 一致 ——
     * 两边对不上时不会报错，只会静默截断多出来的光源。
     *
     * <p><b>循环里为什么用权重而不 break</b>：桌面 GLSL 1.20 其实允许动态 break，
     * 但驱动实现差异大；固定跑 {@code MAX_LIGHTS} 次、把越界槽位的权重置 0，在任何驱动上都一样。
     * 空槽位的 {@code uLightPos} 可能是上一帧的残留，但权重 0 已经把结果乘成 0，
     * 且 {@code max(dist, 0.0001)} 不会产生 NaN。</p>
     *
     * <p><b>天空必须早退</b>：{@code d >= 0.99999} 时反投影得到的是无穷远，
     * {@code world} 会变成 1e30 量级的垃圾值，再拿去算 {@code toLight} 就是
     * 满屏噪点（阶段 1 的天空彩云就是这么来的）。这类像素根本没有几何体，
     * 直接输出原场景色、不参与任何光照。</p>
     *
     * <p><b>为什么用 {@code gl_FragCoord} 取 uv 而不是传 texcoord</b>：
     * 深度副本与颜色副本都是整屏 1:1 拷过来的，窗口坐标除以屏幕尺寸就是精确的
     * 纹理坐标，省掉一组顶点属性（顶点格式只要 POSITION）。</p>
     */
    private static final String FRAGMENT_SRC =
            "#version 120\n"
            + "\n"
            + "uniform sampler2D uDepth;\n"
            + "uniform sampler2D uScene;\n"
            + "uniform vec2  uScreen;\n"
            + "uniform mat4  uInvProj;\n"
            + "uniform mat4  uInvView;\n"
            + "uniform int   uLightCount;\n"
            + "uniform vec3  uLightPos[8];\n"
            + "uniform vec3  uLightDir[8];\n"
            + "uniform vec4  uLightColorRange[8];\n"
            + "uniform vec4  uLightCone[8];\n"
            + "uniform vec4  uLightPlane[8];\n"
            + "uniform int   uHasScene;\n"
            + "\n"
            + "const int   MODE        = 2;\n"
            + "const float NEAR_PLANE  = 0.05;\n"
            + "const float FAR_RANGE   = 256.0;\n"
            + "const float COORD_SCALE = 0.0625;\n"
            + "const float SHADOW_SOFT = 0.35;   // occlusion edge fade width, in world units\n"
            + "const float LAMBERT     = 0.10;   // 0 = flat light, 1 = pure N.L\n"
            + "const float KNEE        = 0.90;\n"
            + "const float LIMIT       = 1.20;\n"
            + "// XXX v5.5.5: GLSL source MUST stay pure ASCII. v5.5.4 put Chinese\n"
            + "// comments in here and NVIDIA's GLSL 120 front-end rejected the whole\n"
            + "// shader (error C0000 syntax error, unexpected $end at token <EOF>),\n"
            + "// which killed every spotlight. If a view bias is ever needed again,\n"
            + "// pass the camera position as a uniform and keep comments ASCII-only.\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 uv = gl_FragCoord.xy / uScreen;\n"
            + "    float d = texture2D(uDepth, uv).r;\n"
            + "\n"
            + "    vec4 scene = texture2D(uScene, uv);\n"
            + "    if (uHasScene == 0) { scene = vec4(0.0); }\n"
            + "\n"
            + "    // sky: infinite depth, no geometry -> pass the scene through untouched\n"
            + "    if (d >= 0.99999) {\n"
            + "        gl_FragColor = vec4(scene.rgb, 1.0);\n"
            + "        return;\n"
            + "    }\n"
            + "\n"
            + "    if (MODE == 0) {\n"
            + "        float linearZ = NEAR_PLANE / max(1.0 - d, 0.000001);\n"
            + "        float v = log(1.0 + linearZ) / log(1.0 + FAR_RANGE);\n"
            + "        gl_FragColor = vec4(vec3(clamp(v, 0.0, 1.0)), 1.0);\n"
            + "        return;\n"
            + "    }\n"
            + "\n"
            + "    vec4 clip = vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);\n"
            + "    vec4 viewPos = uInvProj * clip;\n"
            + "    viewPos /= viewPos.w;\n"
            + "    vec3 world = (uInvView * viewPos).xyz;\n"
            + "\n"
            + "    // rebuild the surface normal from world-position derivatives.\n"
            + "    // without it every face eats the same intensity, which is exactly why\n"
            + "    // the spot looked like a round decal pasted over the screen.\n"
            + "    vec3 dwx = dFdx(world);\n"
            + "    vec3 dwy = dFdy(world);\n"
            + "    vec3 nraw = cross(dwx, dwy);\n"
            + "    float nlen = length(nraw);\n"
            + "    vec3 nrm = (nlen > 1e-9) ? (nraw / nlen) : vec3(0.0, 1.0, 0.0);\n"
            + "\n"
            + "    if (MODE == 1) {\n"
            + "        gl_FragColor = vec4(fract(world * COORD_SCALE), 1.0);\n"
            + "        return;\n"
            + "    }\n"
            + "\n"
            + "    vec3 sum = vec3(0.0);\n"
            + "    for (int i = 0; i < 8; i++) {\n"
            + "        float w = (i < uLightCount) ? 1.0 : 0.0;\n"
            + "        vec3  toLight = uLightPos[i] - world;\n"
            + "        float dist = length(toLight);\n"
            + "        vec3  L = toLight / max(dist, 0.0001);\n"
            + "        float theta = dot(-L, uLightDir[i]);\n"
            + "        float spot = smoothstep(uLightCone[i].y, uLightCone[i].x, theta);\n"
            + "        float atten = 1.0 / (1.0 + 0.07 * dist + 0.005 * dist * dist);\n"
            + "        float rmax = uLightColorRange[i].w;\n"
            + "        atten *= 1.0 - smoothstep(rmax * 0.45, rmax, dist);\n"
            + "        // occlusion half-space, with a soft edge instead of a hard cut.\n"
            + "        // length(plane.xyz) == 0 means the beam has no occluder -> fully visible.\n"
            + "        float plen = length(uLightPlane[i].xyz);\n"
            + "        float cut = dot(uLightPlane[i].xyz, world) - uLightPlane[i].w;\n"
            + "        float soft = smoothstep(-SHADOW_SOFT, 0.0, cut);\n"
            + "        float vis = mix(1.0, soft, step(0.001, plen));\n"
            + "        // N.L wrap: keeps 35% base so a grazing beam still reads as light\n"
            + "        float ndl = abs(dot(nrm, L));\n"
            + "        float ndlw = mix(1.0, ndl, LAMBERT);\n"
            + "        sum += uLightColorRange[i].rgb * (atten * spot * w * vis * ndlw);\n"
            + "    }\n"
            + "\n"
            + "    // soft peak limiter: linear up to KNEE, then exponential approach to LIMIT\n"
            + "    // a single lamp stays below KNEE and is untouched; overlapping lamps\n"
            + "    // are compressed per channel instead of clipping to flat white\n"
            + "    vec3 over = max(sum - vec3(KNEE), vec3(0.0));\n"
            + "    sum = min(sum, vec3(KNEE))\n"
            + "        + (vec3(1.0) - exp(-over / (LIMIT - KNEE))) * (LIMIT - KNEE);\n"
            + "\n"
            + "    if (uHasScene == 1) {\n"
            + "        gl_FragColor = vec4(scene.rgb + sum, 1.0);\n"
            + "    } else {\n"
            + "        gl_FragColor = vec4(sum, 1.0);\n"
            + "    }\n"
            + "}\n";

    private final DepthCapture capture = new DepthCapture();

    private int program = -1;
    private int uniformDepth = -1;
    private int uniformScreen = -1;
    private int uniformInvProj = -1;
    private int uniformInvView = -1;
    private int uniformScene = -1;
    private int uniformHasScene = -1;
    /** uniform 数组的**基地址**；元素 i 的地址 = 基地址 + i（GL 保证数组连续分配） */
    private int uniformLightCount = -1;
    private int uniformLightPos = -1;
    private int uniformLightDir = -1;
    private int uniformLightColorRange = -1;
    private int uniformLightCone = -1;
    private int uniformLightPlane = -1;
    private boolean programFailed;

    /** 光源收集器（真实警报灯 → uniform 数组） */
    private final AlarmSpotlights spotlights = new AlarmSpotlights();

    /** 本帧相机基向量（由 {@link #updateCameraBasis()} 每帧刷新） */
    private double camX;
    private double camY;
    private double camZ;
    private float camPitch;
    private float camYaw;
    private boolean cameraValid;

    /** 复用同一块直接内存，别每帧 new —— 60fps 下就是每秒 60 次分配 */
    private final FloatBuffer projBuf = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer viewBuf = BufferUtils.createFloatBuffer(16);
    /** 矩阵不可逆只报一次 */
    private boolean inverseWarned;
    /** 相机自检只打一次 */
    private static boolean camLogged;
    /** 「GL modelview 被改脏」只提示一次 */
    private static boolean glDirtyLogged;
    /** 本帧插值系数，由 RenderWorldLastEvent 带进来 */
    private float partialTicks;
    /** 重建视图矩阵用的旋转轴（只读，别改） */
    private static final Vector3f AXIS_X = new Vector3f(1.0F, 0.0F, 0.0F);
    private static final Vector3f AXIS_Y = new Vector3f(0.0F, 1.0F, 0.0F);

    /** 每 100 帧重新探一次光影，用户中途开关光影包时能立刻改用降级分支 */
    private static int guardCounter;

    /** 横幅只打一次 */
    private static boolean announced;
    /** 被光影拦下这件事也只提示一次，否则每帧一行日志 */
    private static boolean skipLogged;
    /** 渲染段异常只报一次 */
    private static boolean crashLogged;
    /** 帧号 */
    private int frame;

    /** 探针已执行的次数 / 总预算（v5.1.0：只在启动头两帧确认 blit，之后不再读回） */
    private int probeDone;
    private static final int PROBE_BUDGET = 2;

    /**
     * ★ 用 {@code HIGHEST} 优先级：我们是这条事件总线上第一个跑的渲染监听器，
     * 读 GL 矩阵时还没被 HBM 的实例化粒子渲染器等改脏 modelview。
     * 后面仍然会用实体位置复核一次，双保险。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!ENABLED) {
            return;
        }
        partialTicks = event.getPartialTicks();
        if (!announced) {
            announced = true;
            announce();
        }
        // ★ 光影守卫必须在任何 GLSL 调用之前：OptiFine 的光影包会整屏接管渲染，
        //   自写 program 要么被覆盖、要么直接花屏，这里一律让路。
        if (shaderPackActive()) {
            if (!skipLogged) {
                skipLogged = true;
                JiezhiMod.warn("[SSS] 检测到光影包，SSS 管线主动让路（这是设计行为，不是 bug）。"
                        + "想看深度图请先关掉光影。");
            }
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.getRenderViewEntity() == null) {
            // 回主菜单时把这几十 MB 的显存放掉
            releaseTargets();
            return;
        }

        Framebuffer src = mc.getFramebuffer();
        if (src == null || src.framebufferObject <= 0) {
            return;
        }
        // ★ 整个渲染段包一层兜底：这是调试代码，任何异常都不许把游戏带走。
        //   v4.11.0 那次闪退就是 BufferUnderflowException 冒到事件总线 → Forge 直接判 FATAL。
        try {
            if (!capture.capture(src)) {
                return;
            }
            if (!ensureProgram()) {
                return;
            }
            updateCameraBasis();
            // 光源要在矩阵之后收集：它用同一份相机位置做 64 格筛选
            spotlights.update(mc.world, camX, camY, camZ, partialTicks);
            drawFullscreen(capture.depthTexture(), capture.colorTexture(),
                    capture.colorCopied(), capture.width(), capture.height());

            // ★ v5.1.0 封版：探针只在头两帧跑一次（确认 blit 正确），之后彻底关掉。
            //   读回会打断 GPU 流水线，而且每 120 帧刷一行日志在正式使用中纯属噪音。
            if (probeDone < PROBE_BUDGET) {
                probeDone++;
                capture.logProbe(src, frame);
            }
            frame++;
        } catch (Throwable t) {
            if (!crashLogged) {
                crashLogged = true;
                JiezhiMod.warn("[SSS] 调试渲染抛异常，已就地拦截（游戏继续运行）：", t);
            }
        }
    }

    /**
     * 一次性把"这台机器到底能不能跑"的全部前提打进日志。
     *
     * <p>排查顺序永远是：<b>先看这行在不在</b>（参数到底有没有生效）→ 再看 GL 信息
     * （支持不支持）→ 再看 FBO/着色器那几行（哪一步failed）。</p>
     */
    private static void announce() {
        // 你要的那句：跑到这里就说明注册、事件、开关三件事全通了
        System.out.println("Depth Debug Pass Enabled");
        Minecraft mc = Minecraft.getMinecraft();
        JiezhiMod.warn("[SSS] ===== SSS 深度调试已启用（阶段 3：绑定真实警报灯） =====");
        JiezhiMod.warn("[SSS] GL_VENDOR={}", GL11.glGetString(GL11.GL_VENDOR));
        JiezhiMod.warn("[SSS] GL_RENDERER={}", GL11.glGetString(GL11.GL_RENDERER));
        JiezhiMod.warn("[SSS] GL_VERSION={}", GL11.glGetString(GL11.GL_VERSION));
        JiezhiMod.warn("[SSS] GLSL={}", GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));
        JiezhiMod.warn("[SSS] OpenGL30={} ARB_FBO={} framebufferSupported={}",
                Boolean.valueOf(GLContext.getCapabilities().OpenGL30),
                Boolean.valueOf(GLContext.getCapabilities().GL_ARB_framebuffer_object),
                Boolean.valueOf(OpenGlHelper.framebufferSupported));
        if (mc.getFramebuffer() != null) {
            Framebuffer fb = mc.getFramebuffer();
            JiezhiMod.warn("[SSS] 主FBO id={} {}x{} stencil={} depthRBO={}",
                    Integer.valueOf(fb.framebufferObject),
                    Integer.valueOf(fb.framebufferTextureWidth),
                    Integer.valueOf(fb.framebufferTextureHeight),
                    Boolean.valueOf(fb.isStencilEnabled()),
                    Integer.valueOf(fb.depthBuffer));
        }
    }

    // ------------------------------------------------------------------
    // 光影守卫
    // ------------------------------------------------------------------

    /**
     * OptiFine 光影是否生效。
     *
     * <p>必须反射：OptiFine 常常是启动器层的 tweaker 而不是 mods 里的 jar，
     * 直接 {@code import net.optifine.shaders.Shaders} 在没装时会
     * {@code NoClassDefFoundError}，把整个渲染钩子带崩。</p>
     */
    private static boolean shaderPackActive() {
        if ((guardCounter++ % 100) == 0) {
            shaderPackLoaded = null;
        }
        Boolean cached = shaderPackLoaded;
        if (cached != null) {
            return cached.booleanValue();
        }

        boolean on = false;
        try {
            Class<?> shaders = Class.forName("net.optifine.shaders.Shaders");
            on = Boolean.TRUE.equals(shaders.getField("shaderPackLoaded").get(null));
        } catch (Throwable ignored) {
            on = false;
        }
        if (!on) {
            try {
                Class<?> config = Class.forName("net.optifine.Config");
                on = Boolean.TRUE.equals(config.getMethod("isShaders").invoke(null));
            } catch (Throwable ignored) {
                on = false;
            }
        }
        shaderPackLoaded = Boolean.valueOf(on);
        return on;
    }

    // ------------------------------------------------------------------
    // 着色器
    // ------------------------------------------------------------------

    private boolean ensureProgram() {
        if (program > 0) {
            return true;
        }
        if (programFailed) {
            return false;
        }
        int vs = compile(GL20.GL_VERTEX_SHADER, VERTEX_SRC, "vertex");
        if (vs == 0) {
            programFailed = true;
            return false;
        }
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SRC, "fragment");
        if (fs == 0) {
            GL20.glDeleteShader(vs);
            programFailed = true;
            return false;
        }

        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);

        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            JiezhiMod.warn("[SSS] 深度调试 program 链接失败：{}",
                    GL20.glGetProgramInfoLog(p, 4096));
            GL20.glDeleteProgram(p);
            programFailed = true;
            return false;
        }

        program = p;
        uniformDepth = GL20.glGetUniformLocation(p, "uDepth");
        uniformScreen = GL20.glGetUniformLocation(p, "uScreen");
        uniformInvProj = GL20.glGetUniformLocation(p, "uInvProj");
        uniformInvView = GL20.glGetUniformLocation(p, "uInvView");
        uniformScene = GL20.glGetUniformLocation(p, "uScene");
        uniformHasScene = GL20.glGetUniformLocation(p, "uHasScene");
        uniformLightCount = GL20.glGetUniformLocation(p, "uLightCount");
        // 数组要问 "[0]"：部分驱动只认带下标的名字，返回 -1 时再退回裸名
        uniformLightPos = uniformArray(p, "uLightPos");
        uniformLightDir = uniformArray(p, "uLightDir");
        uniformLightColorRange = uniformArray(p, "uLightColorRange");
        uniformLightCone = uniformArray(p, "uLightCone");
        uniformLightPlane = uniformArray(p, "uLightPlane");
        JiezhiMod.warn("[SSS] 着色器链接成功 program={} uDepth={} uScene={} uScreen={} "
                        + "uInvProj={} uInvView={} uHasScene={} uLightCount={} "
                        + "uLightPos={} uLightDir={} uLightColorRange={} uLightCone={}",
                Integer.valueOf(p), Integer.valueOf(uniformDepth), Integer.valueOf(uniformScene),
                Integer.valueOf(uniformScreen), Integer.valueOf(uniformInvProj),
                Integer.valueOf(uniformInvView), Integer.valueOf(uniformHasScene),
                Integer.valueOf(uniformLightCount), Integer.valueOf(uniformLightPos),
                Integer.valueOf(uniformLightDir), Integer.valueOf(uniformLightColorRange),
                Integer.valueOf(uniformLightCone), Integer.valueOf(uniformLightPlane));
        return true;
    }

    /** 取 uniform 数组基地址：先试 {@code name[0]}，不行再试裸名 */
    private static int uniformArray(int p, String name) {
        int loc = GL20.glGetUniformLocation(p, name + "[0]");
        return loc != -1 ? loc : GL20.glGetUniformLocation(p, name);
    }

    private static int compile(int type, String source, String label) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, source);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) {
            JiezhiMod.warn("[SSS] {} 着色器编译失败：{}",
                    label, GL20.glGetShaderInfoLog(id, 4096));
            GL20.glDeleteShader(id);
            return 0;
        }
        return id;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    /**
     * 整屏画一次：深度(unit0) + 场景色(unit1) → 聚光灯结果。
     *
     * <p>状态处理按 render-safety-guard 的契约来：进入前读回原值，退出按原值还原，
     * 且全部放在 finally 里 —— 这里任何一条 early return 都不许绕过还原。</p>
     *
     * <p><b>纹理单元必须成对保存/还原</b>：原版画完世界后 unit1 上挂着的是光照贴图，
     * 我们把场景色盖上去却不还原，后面 HUD/粒子会拿着一张 2560×1566 的场景图当
     * 光照贴图采样 —— 表现是"后面的特效整片颜色错乱"，而且极难联想到是这里。</p>
     */
    private void drawFullscreen(int depthTexture, int sceneTexture, boolean hasScene,
                                int texWidth, int texHeight) {
        if (program <= 0 || depthTexture <= 0 || texWidth <= 0 || texHeight <= 0) {
            return;
        }

        boolean blendWasOn = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthWasOn = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cullWasOn = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int prevActiveUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevBlendSrc = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int prevBlendDst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);

        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try {
            if (hasScene) {
                // 我们是"整屏重写"：输出就是最终画面，用不透明覆盖
                GlStateManager.disableBlend();
            } else {
                // 颜色副本不可用时的兜底：着色器只输出光，靠加法混合叠到原画面上
                GlStateManager.enableBlend();
                GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
            }
            // 必须关深度测试：我们的四边形在 NDC z=0（窗口深度 0.5），
            // 开着 LEQUAL 会在近处物体上整片被挡掉
            GlStateManager.disableDepth();
            GlStateManager.disableCull();
            GlStateManager.depthMask(false);

            GL20.glUseProgram(program);
            GL20.glUniform1i(uniformDepth, 0);
            GL20.glUniform1i(uniformScene, 1);
            GL20.glUniform1i(uniformHasScene, hasScene ? 1 : 0);
            GL20.glUniform2f(uniformScreen, (float) texWidth, (float) texHeight);

            uploadCameraMatrices();
            uploadLights();

            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(depthTexture);
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.bindTexture(sceneTexture > 0 ? sceneTexture : 0);
            // 顶点着色器不吃纹理坐标，无所谓活动单元，但 Tessellator 会写
            // 顶点属性；切回 0 是历史的稳妥做法（原版自己也这么干）
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
            buffer.pos(-1.0D, -1.0D, 0.0D).endVertex();
            buffer.pos(1.0D, -1.0D, 0.0D).endVertex();
            buffer.pos(1.0D, 1.0D, 0.0D).endVertex();
            buffer.pos(-1.0D, 1.0D, 0.0D).endVertex();
            tessellator.draw();
        } finally {
            GL20.glUseProgram(0);
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.bindTexture(prevTex1);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(prevTex0);
            GlStateManager.setActiveTexture(prevActiveUnit);
            GlStateManager.blendFunc(prevBlendSrc, prevBlendDst);
            GlStateManager.depthMask(depthMask);
            setCap(GL11.GL_CULL_FACE, cullWasOn);
            setCap(GL11.GL_DEPTH_TEST, depthWasOn);
            setCap(GL11.GL_BLEND, blendWasOn);
        }
    }

    /**
     * 每帧刷新一次相机基向量：位置用 tick 插值（跟画面同相位），朝向同样插值。
     *
     * <p>矩阵上传和光源定位都读这份数据，避免两处各算一遍算出不同结果
     * （差半帧就会让聚光灯相对画面抖动）。</p>
     *
     * <h3>★★★ v5.5.3：相机高度必须是<b>眼高</b>，不是脚底</h3>
     * {@code EntityRenderer.setupCameraTransform} 烘进 modelview 的是
     * {@code T(-眼位)}（{@code posY + getEyeHeight()}）。这里如果按 {@code posY}（脚底）重建，
     * 逆视图就会少 1.62 格 ⇒ 着色器把墙面重建到真实位置<b>下方 1.62 格</b>
     * ⇒ 光源相对几何整体"抬高" 1.62 格 ⇒ <b>所有聚光灯的光斑都比该在的位置高 1.6 格左右</b>。
     *
     * <p>推导：{@code P' = P + (cam_错 - cam_真)}，{@code cam_错 = 脚底 = cam_真 - 1.62ŷ}
     * ⇒ {@code P' = P - 1.62ŷ}。11-20 的实机视频量出来光斑悬在灯口上方约 1.7 格（≈1.62+0.5·k），
     * 就是这个常数项 —— 跟 {@code radius} / {@code SEEP} / 光锥角度一点关系都没有。</p>
     */
    private void updateCameraBasis() {
        cameraValid = false;
        Entity viewEntity = Minecraft.getMinecraft().getRenderViewEntity();
        if (viewEntity == null) {
            return;
        }
        double pt = partialTicks;
        camX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * pt;
        // ★ 眼高：脚底 + getEyeHeight()。少加这一项 = 所有光斑整体上浮 1.62 格。
        camY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * pt
                + viewEntity.getEyeHeight();
        camZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * pt;
        camPitch = (float) (viewEntity.prevRotationPitch
                + (viewEntity.rotationPitch - viewEntity.prevRotationPitch) * pt);
        camYaw = (float) (viewEntity.prevRotationYaw
                + (viewEntity.rotationYaw - viewEntity.prevRotationYaw) * pt);
        cameraValid = true;
    }

    /**
     * 阶段 1 的核心：把这一帧真正的「逆投影」「逆视图」传给着色器。
     *
     * <h3>为什么不能只信 GL</h3>
     * 原版在世界 pass 里确实把相机变换烘焙进了 modelview，但
     * <b>{@code RenderWorldLastEvent} 这个时间点上 GL 状态已经不可信了</b>：
     * 同一条事件总线上还排着 HBM 的 {@code InstancedParticleRenderer}、
     * CodeChicken 的 {@code HighlightHandler} 等等，它们各自改过 modelview 却没保证还原。
     * v4.12.0 实测拿到的平移是 {@code (0.04, 1.60, 0.03)} —— 那 1.60 就是眼高，
     * 根本不是相机绝对坐标。同一个整合包里 {@code RenderManager.viewerPos*} 也是
     * {@code (0,0,0)}（它的 mixin 覆盖了 {@code renderWorld}，没走
     * {@code cacheActiveRenderInfo}），所以那两个都不能当基准。
     *
     * <h3>策略：能给就信 GL，给了也要验</h3>
     * <ol>
     *   <li>投影矩阵照读 —— 到这个点没有任何东西改过投影；</li>
     *   <li>视图矩阵先读 GL，然后用「实体插值位置」验算：逆视图的平移分量必须落在
     *       相机 1 格以内（留 1 格给视角摇晃/眼高误差）；</li>
     *   <li>验不过就用实体重建：{@code T(-cam) ∘ Ry(yaw+180) ∘ Rx(pitch)}，
     *       与 {@code EntityRenderer.setupCameraTransform} 的乘法顺序逐项对齐。</li>
     * </ol>
     * 这样既有 GL 那条路的高保真（包含视角摇晃、第三人称偏移），
     * 又不会被别的 mod 留下的脏矩阵带偏。
     *
     * <h3>★ glGetFloat 之后绝不能 flip()</h3>
     * LWJGL 2 的 {@code glGetFloat} 直接写原生内存（{@code memAddress}），
     * <b>不推进 position</b>：{@code clear()} 后 position=0，{@code flip()} 会把 limit 也变成 0，
     * {@code Matrix4f.load} 第一次 {@code get()} 就 {@code BufferUnderflowException}。
     * 所以这里显式 {@code position(0).limit(16)}。
     */
    private void uploadCameraMatrices() {
        if (uniformInvProj < 0 || uniformInvView < 0) {
            return;
        }
        if (!cameraValid) {
            return;
        }

        // ---- 1) 投影：可信 ----
        if (!readMatrix(GL11.GL_PROJECTION_MATRIX, projBuf)) {
            warnOnce("投影矩阵读取失败");
            return;
        }
        Matrix4f proj = new Matrix4f();
        proj.load(projBuf);
        Matrix4f invProj = Matrix4f.invert(proj, new Matrix4f());
        if (!isMatrixOk(invProj)) {
            warnOnce("投影矩阵不可逆");
            return;
        }

        // ---- 2) 视图：读 GL 并用实体位置验证 ----
        Matrix4f invView = null;
        boolean fromGl = false;
        if (readMatrix(GL11.GL_MODELVIEW_MATRIX, viewBuf)) {
            Matrix4f view = new Matrix4f();
            view.load(viewBuf);
            Matrix4f candidate = Matrix4f.invert(view, new Matrix4f());
            if (isMatrixOk(candidate) && nearCamera(candidate, camX, camY, camZ)) {
                invView = candidate;
                fromGl = true;
            } else if (isMatrixOk(candidate) && !glDirtyLogged) {
                glDirtyLogged = true;
                JiezhiMod.warn("[SSS] GL modelview 已被别的渲染器改脏：平移=({}, {}, {})，"
                                + "应为相机 ({}, {}, {}) → 改用实体重建视图矩阵。",
                        f(candidate.m30), f(candidate.m31), f(candidate.m32),
                        f(camX), f(camY), f(camZ));
            }
        }

        // ---- 3) 兜底：用实体重建 ----
        if (invView == null) {
            invView = Matrix4f.invert(buildViewMatrix(camPitch, camYaw, camX, camY, camZ), new Matrix4f());
            if (!isMatrixOk(invView)) {
                warnOnce("重建视图矩阵失败");
                return;
            }
        }

        // ---- 4) 上传 ----
        projBuf.clear();
        invProj.store(projBuf);
        projBuf.flip();

        viewBuf.clear();
        invView.store(viewBuf);
        viewBuf.flip();

        GL20.glUniformMatrix4(uniformInvProj, false, projBuf);
        GL20.glUniformMatrix4(uniformInvView, false, viewBuf);

        if (!camLogged) {
            camLogged = true;
            JiezhiMod.warn("[SSS] 矩阵来源={} 逆视图平移=({}, {}, {}) 相机=({}, {}, {})",
                    fromGl ? "GL(已验证)" : "实体重建",
                    f(invView.m30), f(invView.m31), f(invView.m32),
                    f(camX), f(camY), f(camZ));
        }
    }

    /**
     * 阶段 3：把 {@link AlarmSpotlights} 收集到的真实警报灯光源推给着色器。
     *
     * <h3>数组上传为什么用 {@code base + i}</h3>
     * GL 保证 {@code uniform vec3 u[N]} 的 N 个元素拿到<b>连续</b>的 location，
     * 所以 {@code glUniform3f(base + i, ...)} 就是给第 i 个元素赋值。
     * LWJGL 2 没有"一次传整个数组"的便利方法（那是 {@code glUniform3fv} 带 count 的
     * C 接口），逐元素上传是这版绑定的标准做法。
     *
     * <p><b>数量必须每帧都传</b>：即使 {@code count == 0} 也要上传（着色器靠它把
     * 所有槽位权重打成 0）。少传一次，上一帧的光会一直挂在那儿不灭。</p>
     */
    private void uploadLights() {
        if (uniformLightCount < 0 || uniformLightPos < 0) {
            return;
        }
        int n = this.spotlights.count;
        GL20.glUniform1i(uniformLightCount, n);

        for (int i = 0; i < n; i++) {
            int s = i * 3;
            GL20.glUniform3f(uniformLightPos + i,
                    this.spotlights.pos[s], this.spotlights.pos[s + 1], this.spotlights.pos[s + 2]);
            GL20.glUniform3f(uniformLightDir + i,
                    this.spotlights.dir[s], this.spotlights.dir[s + 1], this.spotlights.dir[s + 2]);
            GL20.glUniform4f(uniformLightColorRange + i,
                    this.spotlights.rgb[s], this.spotlights.rgb[s + 1], this.spotlights.rgb[s + 2],
                    this.spotlights.range[i]);
            GL20.glUniform4f(uniformLightCone + i,
                    this.spotlights.cone[i * 2], this.spotlights.cone[i * 2 + 1], 0.0F, 0.0F);
            GL20.glUniform4f(uniformLightPlane + i,
                    this.spotlights.plane[i * 4], this.spotlights.plane[i * 4 + 1],
                    this.spotlights.plane[i * 4 + 2], this.spotlights.plane[i * 4 + 3]);
        }
    }

    /** 按 {@code EntityRenderer.setupCameraTransform} 的乘法顺序重建视图矩阵 */
    private static Matrix4f buildViewMatrix(float pitchDeg, float yawDeg,
                                            double camX, double camY, double camZ) {
        Matrix4f m = new Matrix4f();
        m.setIdentity();
        // 顺序必须与 GL 一致：glRotatef 是右乘，所以先 pitch 再 yaw 再平移
        m.rotate((float) Math.toRadians(pitchDeg), AXIS_X);
        m.rotate((float) Math.toRadians(yawDeg + 180.0F), AXIS_Y);
        m.translate(new Vector3f((float) (-camX), (float) (-camY), (float) (-camZ)));
        return m;
    }

    /** 把 GL 矩阵读进 buf 并摆正 position/limit（★ 不要 flip） */
    private static boolean readMatrix(int pname, FloatBuffer buf) {
        buf.clear();
        GL11.glGetFloat(pname, buf);
        buf.position(0);
        buf.limit(16);
        return buf.remaining() >= 16;
    }

    private static boolean isMatrixOk(Matrix4f m) {
        return m != null && isFinite(m.m00) && isFinite(m.m30) && isFinite(m.m33);
    }

    /** 逆视图的平移分量就是相机世界坐标，容差 1 格 */
    private static boolean nearCamera(Matrix4f invView, double x, double y, double z) {
        return Math.abs(invView.m30 - x) < 1.0D
                && Math.abs(invView.m31 - y) < 1.0D
                && Math.abs(invView.m32 - z) < 1.0D;
    }

    private static String f(double v) {
        return String.format("%.2f", Double.valueOf(v));
    }

    private void warnOnce(String msg) {
        if (!inverseWarned) {
            inverseWarned = true;
            JiezhiMod.warn("[SSS] {}，阶段 1 反投影跳过。", msg);
        }
    }

    private void releaseTargets() {
        capture.dispose();
        if (program > 0) {
            GL20.glDeleteProgram(program);
            program = -1;
            programFailed = false;
        }
    }

    private static void setCap(int cap, boolean on) {
        if (on) {
            GL11.glEnable(cap);
        } else {
            GL11.glDisable(cap);
        }
    }

    /** NaN / Infinity 检查（矩阵求逆失败时最容易出现的就是这两种值） */
    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}