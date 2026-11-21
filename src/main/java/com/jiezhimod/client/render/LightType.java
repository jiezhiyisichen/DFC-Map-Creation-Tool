package com.jiezhimod.client.render;

import net.minecraft.util.math.Vec3d;

/**
 * 旋转警示灯的<b>安装方式</b> —— 光束方向、光束条数、<b>光锥角度与射程</b>共用的唯一分类器。
 *
 * <h3>为什么要有这个枚举</h3>
 * 灯轴（{@code BlockAlarmLight.FACING} 的法线）有六个方向，但真正影响光学的只有三种姿态：
 * <table border="1">
 *   <tr><th>类型</th><th>判据</th><th>光束</th><th>光锥 内/外</th><th>射程</th></tr>
 *   <tr><td>{@link #FLOOR}</td><td>{@code axis.y > 0.5}</td><td>2 条对扫，水平面旋转</td>
 *       <td>35° / 55°</td><td>8 格</td></tr>
 *   <tr><td>{@link #WALL}</td><td>其余（{@code |axis.y| < 0.5}）</td><td><b>1 条</b>垂直上下扫射</td>
 *       <td>15° / 45°（贴墙）</td><td>10 格</td></tr>
 *   <tr><td>{@link #CEILING}</td><td>{@code axis.y < -0.5}</td><td><b>1 条</b>，垂直向下</td>
 *       <td>45° / 75°（顶灯）</td><td>10 格</td></tr>
 * </table>
 *
 * <h3>★★ 为什么光锥必须按类型分档（v5.0.0 定案）</h3>
 * 之前三种姿态共用一组 35°/55° + 8 格。对立杆灯这是对的（光要罩到灯脚附近的地面），
 * 但挂壁灯是<b>贴墙水平射出</b>的：55° 外角等于把半个球壳扣在墙上，
 * 光斑变成一只巨大的喇叭，把整面墙和墙前两格全糊成一片。
 *
 * <p>物理上也不该一样：挂壁灯的灯罩是<b>单向开口</b>的探照灯，本来就该窄；
 * 立杆灯是全向旋转灯；吊顶灯是散射顶灯。三者的锥角相差一倍以上才合理。</p>
 *
 * <h3>★★ 为什么严格按 {@code axis.y} 的符号分，而不是"看方块贴在哪一面"</h3>
 * {@code FACING} 本身就是"灯口朝外"，贴地 → {@code UP}、贴顶 → {@code DOWN}、贴墙 → 水平。
 * 拿它当唯一判据，就不需要再去查邻居方块是什么 —— 玩家用调试棒改朝向时，
 * 分类会自动跟着变，不会出现"改完朝向还在按旧姿态发光"。
 */
public enum LightType {

    /** 立杆：贴地朝上装，大角度、中射程，光束在地面附近对扫 */
    FLOOR(35.0F, 55.0F, 8.0F, "立杆"),
    /**
     * 挂壁：贴墙朝外装，<b>单束完整贴墙圆环</b>（v5.5.9 定案）。
     *
     * <h3>★★★ v5.5.9：35° → 45° 外角，恢复 5.4.8 的 15/45</h3>
     * 光斑公式回滚到 5.4.8（无 yOffset、SEEP=0.2，有效光斑半径 ≈ R/SEEP = 2.5 格的
     * 大圆环，有上有下）；外角配 45° 才不会把上下两端裁掉。射程 10 格、单束不变；
     * 裁剪平面已对 WALL 停用（见 AlarmSpotlights.trace）。
     */
    WALL(15.0F, 45.0F, 10.0F, "挂壁"),
    /** 吊挂：贴天花板朝下装，顶灯模式 —— 宽锥、长射程、垂直向下 */
    CEILING(45.0F, 75.0F, 10.0F, "吊装");

    /** {@code 0.5} 这个阈值与 v4.x 的 {@code isWallMounted} 完全一致，别改 */
    private static final double AXIS_Y_THRESHOLD = 0.5D;

    private final float cosInner;
    private final float cosOuter;
    private final float range;
    private final String label;

    LightType(float innerDeg, float outerDeg, float range, String label) {
        // 着色器要的是 cos 值（theta 越大越靠近锥轴），角度只在源码里写，转换只做一次
        this.cosInner = (float) Math.cos(Math.toRadians(innerDeg));
        this.cosOuter = (float) Math.cos(Math.toRadians(outerDeg));
        this.range = range;
        this.label = label;
    }

    /** 日志用的中文短名（"立杆 / 挂壁 / 吊装"） */
    public String label() {
        return this.label;
    }

    /** 内锥半角的余弦（满亮边界），直接进 {@code uLightCone[i].x} */
    public float cosInner() {
        return this.cosInner;
    }

    /** 外锥半角的余弦（渐隐到 0 的边界），直接进 {@code uLightCone[i].y} */
    public float cosOuter() {
        return this.cosOuter;
    }

    /** 射程（格），直接进 {@code uLightColorRange[i].w} */
    public float range() {
        return this.range;
    }

    /**
     * 由灯轴（已归一化的 {@code FACING} 方向向量）判安装方式。
     *
     * @param axis 灯口朝外的单位向量；{@code null} 视为挂壁（最保守：窄锥、单束、不旋转）
     */
    public static LightType classify(Vec3d axis) {
        double y = (axis == null) ? 0.0D : axis.y;
        if (y > AXIS_Y_THRESHOLD) {
            return FLOOR;
        }
        if (y < -AXIS_Y_THRESHOLD) {
            return CEILING;
        }
        return WALL;
    }

    /** 是否挂壁装（与 {@code axis.y} 阈值同源，别另写一份判断） */
    public boolean wallMounted() {
        return this == WALL;
    }

    /**
     * 这盏灯本帧要发几条光束。
     *
     * <ul>
     *   <li><b>立杆 2 条</b>：径向平面是水平面 ⇒ 两条水平光束射向相反方向，地面两瓣都看得见；</li>
     *   <li><b>挂壁 1 条</b>（v5.4.7）：只发一束光，避免左右两束飞出视野；</li>
     *   <li><b>吊装 1 条</b>：垂直向下，单束。</li>
     * </ul>
     */
    public int beamCount() {
        return this == FLOOR ? 2 : 1;
    }
}
