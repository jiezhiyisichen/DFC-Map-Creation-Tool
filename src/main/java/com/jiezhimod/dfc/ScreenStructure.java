package com.jiezhimod.dfc;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

/**
 * 4x3x1 显示器多方块的几何计算（纯函数）。
 *
 * 【为什么单独抽出来】
 * 多方块最容易错的就是"从任意一格反推原点"这一步：面板是有朝向的，
 * 同样一个 part 编号，在朝向不同时对应的世界坐标完全不同。
 * 并且这里的坐标换算还要**同 TESR 的渲染对齐** —— 两者一旦不一致，
 * 症状是"方块在一处、屏幕画在另一处"（见 {@link #anchor} 的说明）。
 * 全部做成纯函数，离线就能把 4 个朝向 × 12 个 part 全断言一遍。
 *
 * 【吊装语义】part 0 在结构的**左上角**（贴着天花板的那一行的最左格），
 * 结构从这里**向下**铺 3 行、**向右**铺 4 列。
 * 玩家对着天花板下表面放置时，那一格就是 part 0，整块屏幕挂在头顶 ——
 * 绕**顶边**向下倾斜后屏幕正对斜下方，站在下面正好抬头看。
 *
 * 坐标约定（以 facing = NORTH 为例，面板正面朝北，玩家站在南边仰头看）：
 *   right = facing.rotateY()   = EAST（观看者的右手方向）
 *   down  = DOWN（row 越大越靠下）
 *   part = row * 4 + col，part 0 在**左上角**。
 */
public final class ScreenStructure {

    public static final int COLS = 4;
    public static final int ROWS = 3;
    public static final int PARTS = COLS * ROWS;

    /** 倾斜档数：0 / 15 / 30 / 45 / 60 度 */
    public static final int TILT_STEPS = 5;
    public static final int TILT_STEP_DEG = 15;
    /** 平视时落在这一档（30 度）—— 吊装显示器最常见的倾角 */
    public static final int TILT_DEFAULT = 2;

    private ScreenStructure() {
    }

    public static int part(int col, int row) {
        return row * COLS + col;
    }

    public static int colOf(int part) {
        return part % COLS;
    }

    public static int rowOf(int part) {
        return part / COLS;
    }

    public static boolean isValidPart(int part) {
        return part >= 0 && part < PARTS;
    }

    /** 面板的"右"方向。只对水平朝向有意义。 */
    public static EnumFacing rightOf(EnumFacing facing) {
        if (facing == null || facing.getAxis() == EnumFacing.Axis.Y) {
            return EnumFacing.EAST;
        }
        return facing.rotateY();
    }

    // ==================== 世界坐标 ====================

    /**
     * 从原点（part 0）+ part 编号，算出该格的世界坐标。
     * row 向下走：{@code offset(DOWN, row)} —— DOWN 本身就是负方向，不能再加负号。
     */
    public static BlockPos partPos(BlockPos origin, int part, EnumFacing facing) {
        if (origin == null || !isValidPart(part)) {
            return null;
        }
        EnumFacing right = rightOf(facing);
        return origin.offset(right, colOf(part)).offset(EnumFacing.DOWN, rowOf(part));
    }

    /**
     * 从任意一格 + 它的 part 编号，反推出结构原点（part 0 所在格）。
     *
     * 【注意符号不对称，不是笔误】
     * 水平方向用 right 的**反方向**（offset(right, -col)），
     * 垂直方向用 **UP 的正方向**（offset(UP, +row)）——
     * 因为 partPos 是向下铺的，反推自然要向上退回去。
     * 这里写成 -row 会让 part=4 算到错误的位置，
     * 症状是拆除时扫描不到控制器（自测里那条互逆断言抓的就是它）。
     */
    public static BlockPos originOf(BlockPos pos, int part, EnumFacing facing) {
        if (pos == null || !isValidPart(part)) {
            return null;
        }
        EnumFacing right = rightOf(facing);
        return pos.offset(right, -colOf(part)).offset(EnumFacing.UP, rowOf(part));
    }

    // ==================== 渲染锚点（必须与世界占位严格一致） ====================

    /**
     * facing -> 渲染时绕 Y 轴的旋转角。
     *
     * 渲染本地坐标系里 **-z 是面板正面**，所以要把它转到 facing：
     *   NORTH: 0 / SOUTH: 180 / EAST: 270 / WEST: 90
     *
     * 放在这里而不是渲染器里，是为了让自测能验证"渲染坐标与方块占位一致"。
     */
    public static float yawOf(EnumFacing facing) {
        if (facing == null) {
            return 0.0F;
        }
        switch (facing) {
            case SOUTH:
                return 180.0F;
            case EAST:
                return 270.0F;
            case WEST:
                return 90.0F;
            case NORTH:
            default:
                return 0.0F;
        }
    }

    /** 把本地坐标 (x, y, z) 按 facing 绕 Y 轴旋转；本地 -z 为正面的约定见 {@link #yawOf} */
    public static float[] rotateY(float x, float y, float z, EnumFacing facing) {
        double rad = Math.toRadians(yawOf(facing));
        double c = Math.cos(rad);
        double s = Math.sin(rad);
        return new float[]{(float) (x * c + z * s), (float) y, (float) (-x * s + z * c)};
    }

    /**
     * 本地坐标绕 X 轴俯仰（就是屏幕的下倾角），与 GL 的 {@code rotate(deg,1,0,0)} 完全同构：
     *   y' = y*cos - z*sin ;  z' = y*sin + z*cos
     *
     * 【符号约定：负角 = 屏幕低头，正角 = 屏幕抬头】
     * 本地 -z 是面板正面，法线 n = (0,0,-1)。代入上式：
     *   rotate(+deg) -> n' = (0, +sin, -cos)  **朝上**（屏幕仰望天花板）
     *   rotate(-deg) -> n' = (0, -sin, -cos)  **朝下**（屏幕俯视下方的玩家）
     * 吊装显示器必须让下方的玩家看到屏幕内容，所以渲染时用 **-tilt**。
     * 早期版本用了 +tilt，症状是"屏幕好像朝斜上方的另一边"，站在下面根本读不到数据。
     */
    public static float[] rotateX(float x, float y, float z, float deg) {
        double rad = Math.toRadians(deg);
        double c = Math.cos(rad);
        double s = Math.sin(rad);
        return new float[]{(float) x, (float) (y * c - z * s), (float) (y * s + z * c)};
    }

    /**
     * 屏幕法线（本地 -z）在给定下倾角下、经 {@link #rotateX} 之后的世界方向。
     * y 分量为负表示"面朝斜下方" —— 吊装可见性的判据，自测直接断言它。
     */
    public static float[] tiltedNormal(float tiltDeg) {
        return rotateX(0.0F, 0.0F, -1.0F, tiltDeg);
    }

    /**
     * part 在面板本地坐标系里的中心偏移，**原点取面板的几何中心**。
     * x 向右为正、y 向上为正（所以 row 越大 y 越小 —— 向下）。
     *
     * 取几何中心而不是 part 0 的角，是这次错位 bug 的根治办法：
     * 中心对称后 x∈[-2,2]、y∈[-1.5,1.5]，绕任意轴旋转都不会被甩到别的象限。
     */
    public static float[] localOf(int part) {
        return new float[]{
                (float) colOf(part) - (COLS - 1) / 2.0F,
                (ROWS - 1) / 2.0F - (float) rowOf(part),
                0.0F};
    }

    /**
     * 面板几何中心相对 part 0 方块**最小角**的世界偏移。
     * TESR 拿到的 x/y/z 正是这个最小角，所以：渲染锚点 = 方块坐标 + 本偏移。
     */
    public static double[] anchor(EnumFacing facing) {
        EnumFacing right = rightOf(facing);
        return new double[]{
                0.5D + right.getFrontOffsetX() * (COLS - 1) / 2.0D,
                0.5D - (ROWS - 1) / 2.0D,
                0.5D + right.getFrontOffsetZ() * (COLS - 1) / 2.0D};
    }

    // ==================== 倾斜 ====================

    /**
     * 玩家放置时的俯仰角 -> 倾斜档。
     *
     * Minecraft 的 rotationPitch：负数 = 抬头，正数 = 低头。
     * 吊装语义下：越低头看，屏幕越往下倾（更接近水平吊在天花板上）。
     * 平视（pitch = 0）落在 30 度这一档。
     */
    public static int tiltFromPitch(float pitch) {
        int t = Math.round((pitch + 30.0F) / TILT_STEP_DEG);
        if (t < 0) {
            t = 0;
        }
        if (t > TILT_STEPS - 1) {
            t = TILT_STEPS - 1;
        }
        return t;
    }

    /** 倾斜档 -> 角度（度） */
    public static float tiltDegrees(int tilt) {
        int t = tilt;
        if (t < 0) {
            t = 0;
        }
        if (t > TILT_STEPS - 1) {
            t = TILT_STEPS - 1;
        }
        return t * (float) TILT_STEP_DEG;
    }

    /** 显示区域的世界尺寸（宽 = COLS 格，高 = ROWS 格） */
    public static float panelWidthBlocks() {
        return COLS;
    }

    public static float panelHeightBlocks() {
        return ROWS;
    }

    // ==================== 屏幕 quad 的 UV 分配 ====================

    /**
     * 屏幕正面（本地 -z）quad 的 UV 分配。返回顺序 = {u@x0, u@x1, v@y0, v@y1}，
     * 其中 x0/x1 与 y0/y1 是渲染本地坐标里的左右/下上边界。
     *
     * 【为什么 u 是反的 —— 这是"整屏左右镜像"的根治】
     * 本地 -z 是正面，观察者站在正面外侧朝 +z 看。该观察者的"右"向量
     *   r = forward × up = (0,0,1) × (0,1,0) = (-1,0,0) = **世界 -x**
     * （与 GL 默认相机 f=(0,0,-1) 时 r=+x 同一套右手约定）。
     * 于是本地 **+x 落在观察者的左手侧**。
     * 纹理 u 是跟着图像列号走的（u=0 = 图像最左列），要让"图像的左边"
     * 出现在"观察者的左边"，就必须把 u=0 配给 x1、把 u=1 配给 x0。
     * 早期版本按直觉把 u=1 配给 x1，症状是屏幕内容可以显示但**全是反的**：
     * 文字反写、左右栏对调（EVENT LOG 跑到左边、温度块跑到右边） ——
     * 这份自测断言就是给那次回归立的桩。
     *
     * 【v 不用反向】
     * 上传缓冲第 0 行 = AWT 图像顶行，经 glTexSubImage2D 后落在纹理 v=0，
     * 也就是 v 越小越靠图像顶部；而屏幕 y1（上边）本就该取 v=0，
     * 所以 v 随 y 递减即可，与 u 的方向性恰好相反。
     */
    public static float[] panelUv() {
        return new float[]{1.0F, 0.0F, 1.0F, 0.0F};
    }
}
