package com.jiezhimod.client.util;

/**
 * GUI 填充的**纯几何**：圆角矩形每一行的左右内缩量。
 *
 * 【为什么单独一个类、一个 Minecraft 类都不 import】
 * v3.6~v3.9.1 的底纹一直画不出来，先后怀疑过三角化、GL 状态、纹理开关，
 * 直到把"能显示的路径"和"不能显示的路径"摆在一起对比，才定位到 **GL_TRIANGLES**
 * （详见 {@link GuiDraw} 的类注释）。换成逐行四边形之后，几何简化成
 * "第 i 行左右各缩多少"—— 这是**可以脱离 GL 直接断言**的纯计算：
 * 行数、内缩单调性、覆盖面积都能离线验，不用进游戏碰运气。
 */
public final class GuiGeometry {

    private GuiGeometry() {
    }

    /**
     * 圆角矩形第 {@code row} 行（0 起，1 逻辑像素高）的左右内缩量。
     *
     * 第 0 行与最后一行缩得最多，中间若干行完全不缩 —— 拼出来就是圆角。
     * 用标准圆弧：以行的**中心**到最近水平边的距离 dy 判断，
     * {@code dy >= r} 不缩；否则缩 {@code r - sqrt(r² - (r-dy)²)}。
     *
     * @param radius 已经收敛过的圆角半径（&lt;= 短边一半）
     * @param height 矩形高度（逻辑像素）
     * @param row    行号，超出范围返回 0
     */
    public static float rowInset(float radius, float height, int row) {
        if (radius <= 0.0F || height <= 0.0F || row < 0 || row >= (int) Math.ceil(height)) {
            return 0.0F;
        }
        double dy = Math.min(row + 0.5D, height - (row + 0.5D));
        if (dy >= radius) {
            return 0.0F;
        }
        double d = radius - dy;
        double inner = radius * radius - d * d;
        if (inner <= 0.0D) {
            return radius;
        }
        return (float) (radius - Math.sqrt(inner));
    }

    /** 逐行拼出来的圆角矩形覆盖面积（离线校验"没漏行、没算错"用） */
    public static double coveredArea(float width, float height, float radius) {
        double r = Math.max(0.0F, Math.min(radius, Math.min(width, height) / 2.0F));
        double area = 0.0D;
        int rows = (int) Math.ceil(height);
        for (int i = 0; i < rows; i++) {
            double w = width - 2.0D * rowInset((float) r, height, i);
            if (w > 0.0D) {
                area += w;
            }
        }
        return area;
    }
}
