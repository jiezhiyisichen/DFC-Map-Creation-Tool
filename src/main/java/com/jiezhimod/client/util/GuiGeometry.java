package com.jiezhimod.client.util;

/**
 * GUI 用到的**纯几何**计算：把"矩形 / 圆角矩形"拆成三角形列表。
 *
 * 【为什么单独一个类、而且一个 Minecraft 类都不 import】
 * v3.6 及以前，三角化写错导致整块面板在游戏里完全透明（详见 {@link GuiDraw} 的注释）。
 * 这类"退化三角形"的 bug 用眼睛很难发现（在代码里看顶点数量是对的），
 * 唯一的可靠办法是把它变成**可离线断言的数据**：
 * 几何不碰 GL、不 import 任何 MC 类，{@code SelfTest} 就能直接
 * "顶点数是 3 的倍数 / 每个三角形面积都不为 0 / 总面积接近 w·h" 一路查过去。
 *
 * 顶点数组格式：{@code [x0,y0, x1,y1, x2,y2, ...]}，每 3 个顶点 = 1 个三角形。
 */
public final class GuiGeometry {

    private GuiGeometry() {
    }

    /** 实心矩形 → 2 个三角形、6 个顶点 */
    public static float[] rectTriangles(float x1, float y1, float x2, float y2) {
        return new float[]{
                x1, y1, x2, y1, x2, y2,
                x1, y1, x2, y2, x1, y2
        };
    }

    /**
     * 圆角矩形 → 三角扇，**每个三角形都重新带一次中心点**。
     *
     * 圆角矩形是凸多边形，从中心向相邻两个轮廓点张成的三角形必然铺满、且互不重叠。
     * 顶点数 = 轮廓点数 × 3 × 2。
     */
    public static float[] roundedRectTriangles(float x, float y, float w, float h, float radius) {
        float r = Math.max(0.0F, Math.min(radius, Math.min(w, h) / 2.0F));
        if (r <= 0.0F) {
            return rectTriangles(x, y, x + w, y + h);
        }
        int[] segs = arcSegments(r);
        int n = outlinePointCount(segs);

        float[] pts = new float[n * 2];
        for (int i = 0; i < n; i++) {
            float[] p = outlinePoint(x, y, w, h, r, segs, i);
            pts[i * 2] = p[0];
            pts[i * 2 + 1] = p[1];
        }

        float cx = x + w / 2.0F;
        float cy = y + h / 2.0F;
        float[] out = new float[n * 6];
        int used = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;   // 最后一个点连回第一个点，轮廓才闭合
            float ax = pts[i * 2];
            float ay = pts[i * 2 + 1];
            float bx = pts[j * 2];
            float by = pts[j * 2 + 1];
            // 半径正好等于短边一半时（"胶囊形"），首尾轮廓点会重合 ⇒ 这个扇形三角形面积 0。
            // 零面积三角形既画不出东西、还会让"三角形不许退化"的自测误报，直接不发。
            if (Math.abs((ax - cx) * (by - cy) - (bx - cx) * (ay - cy)) <= 1.0E-6F) {
                continue;
            }
            out[used] = cx;
            out[used + 1] = cy;
            out[used + 2] = ax;
            out[used + 3] = ay;
            out[used + 4] = bx;
            out[used + 5] = by;
            used += 6;
        }
        return java.util.Arrays.copyOf(out, used);
    }

    /** 每个圆角的分段数。半径越小分段越少，避免小尺寸控件白白堆顶点。 */
    static int[] arcSegments(float r) {
        int seg = (int) Math.ceil(r / 1.5D);
        if (seg < 3) {
            seg = 3;
        }
        if (seg > 12) {
            seg = 12;
        }
        return new int[]{seg, seg, seg, seg};
    }

    /** 轮廓点总数：四角各 seg+1 个端点（含四个直边与圆角的衔接点） */
    static int outlinePointCount(int[] segs) {
        int n = 0;
        for (int s : segs) {
            n += s + 1;
        }
        return n;
    }

    /**
     * 取轮廓上的第 i 个点（屏幕坐标 y 向下）。
     *
     * 四段圆弧各自转 90°，段与段之间靠"上一个终点 = 下一个起点"衔接，
     * 直边（上下左右四条）由扇形自动跨接，因此无需额外插入直边顶点。
     */
    static float[] outlinePoint(float x, float y, float w, float h, float r, int[] segs, int i) {
        int corner = 0;
        int acc = 0;
        while (corner < 3 && i >= acc + segs[corner] + 1) {
            acc += segs[corner] + 1;
            corner++;
        }
        int step = i - acc;
        int seg = segs[corner];
        double t = (double) step / (double) seg;

        // 0=左上 1=右上 2=右下 3=左下；起始角与圆心按顺时针依次排布
        double startDeg;
        float ccx;
        float ccy;
        switch (corner) {
            case 0:
                startDeg = 180.0D;
                ccx = x + r;
                ccy = y + r;
                break;
            case 1:
                startDeg = 270.0D;
                ccx = x + w - r;
                ccy = y + r;
                break;
            case 2:
                startDeg = 0.0D;
                ccx = x + w - r;
                ccy = y + h - r;
                break;
            default:
                startDeg = 90.0D;
                ccx = x + r;
                ccy = y + h - r;
                break;
        }
        double ang = (startDeg + 90.0D * t) * Math.PI / 180.0D;
        return new float[]{
                (float) (ccx + Math.cos(ang) * r),
                (float) (ccy + Math.sin(ang) * r)
        };
    }
}
