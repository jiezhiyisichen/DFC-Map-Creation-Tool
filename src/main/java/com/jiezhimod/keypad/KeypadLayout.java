package com.jiezhimod.keypad;

/**
 * 密码盘面板的**纯几何**：键盘格的位置、以及"点到哪儿算按了哪个键"。
 *
 * 【为什么单独一个类、一个 Minecraft 类都不 import】
 * 这里既是**渲染**的依据（画键位），又是**命中判定**的依据（右键点到哪个键）。
 * 两边只要有一处算错，玩家就会"看着按了 5、实际输入的是 6"。
 * 把布局做成纯数据之后，{@code SelfTest} 可以直接断言
 * "12 个键互不重叠 / 都在面板内 / 命中映射和画出来的矩形完全一致"。
 *
 * 坐标系：逻辑像素，原点在面板左上角，面板尺寸 {@link #PANEL_W} × {@link #PANEL_H}。
 * 渲染侧按这个尺寸画到 {@code ScreenCanvas} 上；
 * 方块侧用 {@link #hitWorld} 把方块内的 hitX/hitZ 换算成同一套坐标再查表。
 */
public final class KeypadLayout {

    /** 面板逻辑尺寸（ScreenCanvas 的画布就是 256×192） */
    public static final int PANEL_W = 256;
    public static final int PANEL_H = 192;

    /**
     * 面板在方块顶面占的范围（方块内 0~1 坐标）。
     * 四周各留 6% 的边，看起来像"面板嵌进台面里"。
     */
    public static final float PANEL_MIN = 0.06F;
    public static final float PANEL_MAX = 0.94F;

    public static final int COLS = 4;
    public static final int ROWS = 3;

    /** 键盘左上角与单元格尺寸（逻辑像素） */
    public static final int KEY_X = 9;
    public static final int KEY_Y = 56;
    public static final int KEY_W = 38;
    public static final int KEY_H = 38;
    public static final int KEY_GAP = 4;

    public static final int KEY_COUNT = COLS * ROWS;

    /** 10 个数字键之后的两个功能键 */
    public static final int KEY_CLEAR = 10;
    public static final int KEY_ENTER = 11;

    /** 便签纸在面板上的位置（右下那一块） */
    public static final int NOTE_X = 180;
    public static final int NOTE_Y = 58;
    public static final int NOTE_W = 68;
    public static final int NOTE_H = 76;

    private static final String[] LABELS = {
            "1", "2", "3", "4",
            "5", "6", "7", "8",
            "9", "0", "清除", "输入"
    };

    private KeypadLayout() {
    }

    public static String label(int key) {
        if (key < 0 || key >= LABELS.length) {
            return "";
        }
        return LABELS[key];
    }

    /**
     * 键号 -> 真正输入的数字；功能键返回 -1。
     *
     * 【★★ 这里必须和 {@link #LABELS} 严格对应，别再按"下标等于数字"想当然】
     * 键面是按**电话键盘**排的：1 2 3 / 4 5 6 / 7 8 9 / 0 清除 输入。
     * 所以 0 在**下标 9**，而下标 0 是数字 1。
     *
     * v3.9.6 之前这里写的是 {@code return key}（下标即数字），
     * 结果实机就是"点 0 出来 9、点 1 出来 0、点 4 出来 3"——
     * 每一个键都偏移了一位。渲染读 LABELS、输入读 digit，两边各算各的就会这样。
     * 现在唯一真源是"标签里写的是什么，输入的就是什么"（自测有断言钉住）。
     */
    public static int digit(int key) {
        if (key < 0 || key > 9) {
            return -1;
        }
        // LABELS 是 1,2,…,9,0：下标 0..8 -> 数字 1..9，下标 9 -> 数字 0
        return key == 9 ? 0 : key + 1;
    }

    public static int keyX(int key) {
        return KEY_X + (key % COLS) * (KEY_W + KEY_GAP);
    }

    public static int keyY(int key) {
        return KEY_Y + (key / COLS) * (KEY_H + KEY_GAP);
    }

    /** 键盘区整体占的矩形（渲染底板用） */
    public static int padRight() {
        return keyX(KEY_COUNT - 1) + KEY_W;
    }

    public static int padBottom() {
        return keyY(KEY_COUNT - 1) + KEY_H;
    }

    /**
     * 面板内相对坐标（0~1）→ 键号；没点中返回 -1。
     *
     * 【为什么不用"整除分列"】按键之间有空隙（{@link #KEY_GAP}），
     * 整除会把空隙也算进相邻的键，手指按在缝里就会"误触"。
     * 这里逐个矩形判一遍，塞缝返回 -1 表示这次点击没落到任何键上。
     */
    public static int hitPanel(double u, double v) {
        if (u < 0.0D || u > 1.0D || v < 0.0D || v > 1.0D) {
            return -1;
        }
        int cx = (int) Math.floor(u * PANEL_W);
        int cy = (int) Math.floor(v * PANEL_H);
        for (int i = 0; i < KEY_COUNT; i++) {
            int x = keyX(i);
            int y = keyY(i);
            if (cx >= x && cx < x + KEY_W && cy >= y && cy < y + KEY_H) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 方块内的命中点（{@code onBlockActivated} 给的 hitX / hitZ，0~1）→ 键号。
     * 点到面板外（台面边缘）返回 -1。
     */
    public static int hitWorld(float hitX, float hitZ) {
        double span = PANEL_MAX - PANEL_MIN;
        double u = (hitX - PANEL_MIN) / span;
        double v = (hitZ - PANEL_MIN) / span;
        return hitPanel(u, v);
    }
}
