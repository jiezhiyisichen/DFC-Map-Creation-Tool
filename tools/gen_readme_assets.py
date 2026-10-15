# -*- coding: utf-8 -*-
"""
生成 README 用的配图（banner / 物品一览 / 方块一览），并把离屏渲染的屏幕截图
按语义化文件名整理到 screenshots/。

用法（在项目根执行）：
    python tools/gen_readme_assets.py

输入：
    tools/canvastest/dump/*.png  —— CanvasDump 导出的 768x576 屏幕离屏渲染图
    src/main/resources/assets/jiezhimod/textures/items/*.png   —— 16x16 物品贴图
    src/main/resources/assets/jiezhimod/textures/blocks/*.png  —— 16x16 方块贴图
输出：
    screenshots/*.png
"""
import os
import shutil

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DUMP = os.path.join(ROOT, "tools", "canvastest", "dump")
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod", "textures")
OUT = os.path.join(ROOT, "screenshots")

FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"
FONT_REG = "C:/Windows/Fonts/msyh.ttc"
FONT_MONO = "C:/Windows/Fonts/consola.ttf"

# 主题色（与模组屏幕同一套配色）
BG0 = (9, 14, 20)
BG1 = (16, 26, 36)
CYAN = (72, 214, 255)
ACCENT = (255, 107, 214)
OKC = (94, 226, 154)
WARN = (255, 197, 92)
CRIT = (255, 106, 106)
TXT = (222, 234, 242)
DIM = (128, 152, 170)


def font(path, size):
    return ImageFont.truetype(path, size, index=0)


def gradient(w, h, c0, c1, diagonal=True):
    """竖向（或斜向）线性渐变底"""
    base = Image.new("RGB", (w, h), c0)
    d = ImageDraw.Draw(base)
    for y in range(h):
        t = y / max(1, h - 1)
        col = tuple(int(c0[i] + (c1[i] - c0[i]) * t) for i in range(3))
        d.line([(0, y), (w, y)], fill=col)
    return base


def grid(canvas, step=32, color=(255, 255, 255, 8)):
    ov = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for x in range(0, canvas.width, step):
        d.line([(x, 0), (x, canvas.height)], fill=color)
    for y in range(0, canvas.height, step):
        d.line([(0, y), (canvas.width, y)], fill=color)
    canvas.alpha_composite(ov)


def panel(canvas, box, radius=10, fill=(255, 255, 255, 10), outline=(255, 255, 255, 26), width=1):
    ov = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(ov).rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)
    canvas.alpha_composite(ov)


def text(d, xy, s, f, fill, anchor="la", shadow=None):
    if shadow:
        d.text((xy[0] + 1, xy[1] + 1), s, font=f, fill=shadow, anchor=anchor)
    d.text(xy, s, font=f, fill=fill, anchor=anchor)


def text_w(d, s, f):
    return d.textlength(s, font=f)


def fit_text(d, s, f_path, size, max_w, min_size=10):
    """字号自适应：放不下就逐号缩小"""
    while size > min_size:
        f = font(f_path, size)
        if text_w(d, s, f) <= max_w:
            return f
        size -= 1
    return font(f_path, min_size)


# ----------------------------------------------------------------------------
# 1) 整理屏幕截图
# ----------------------------------------------------------------------------
SCREENS = [
    # (dump 文件名, 输出名, 说明)
    ("dfc_canvas_boot.png",      "dfc-boot.png",           "开机 · 节枝OS"),
    ("dfc_canvas_console.png",   "dfc-console.png",        "开机自检控制台"),
    ("dfc_canvas_main.png",      "dfc-main.png",           "核心页（正常）"),
    ("dfc_canvas_main_alert.png", "dfc-main-alert.png",     "核心页（崩溃告警）"),
    ("dfc_canvas_main_critical.png", "dfc-main-critical.png", "核心页（危急）"),
    ("dfc_canvas_main_stab_low.png", "dfc-main-stab-low.png", "核心页（稳定度偏低）"),
    ("dfc_canvas_main_offline.png", "dfc-offline.png",       "离线页（未到 100°C）"),
    ("dfc_canvas_main_nolink.png", "dfc-nolink.png",        "未绑定数据源"),
    ("dfc_canvas_main_log_wrap.png", "dfc-log-wrap.png",     "事件日志折行"),
    ("dfc_canvas_components.png", "dfc-components.png",     "部件监视器页"),
    ("dfc_canvas_components_nolink.png", "dfc-components-nolink.png", "部件页（未连接）"),
    ("dfc_canvas_custom.png",    "dfc-custom.png",         "自定义显示屏页"),
    ("dfc_canvas_custom_empty.png", "dfc-custom-empty.png",  "自定义屏（空态）"),
    ("dfc_canvas_fault_start.png", "dfc-fault-301.png",      "熔毁报错 301"),
    ("dfc_canvas_off.png",       "dfc-poweroff.png",       "关机"),
    ("dfc_dial_main.png",        "dfc-dial.png",           "崩溃圆盘（放大）"),
    ("dfc_dial_main_critical.png", "dfc-dial-critical.png",  "崩溃圆盘 · 危急（放大）"),
]


def collect_screens():
    n = 0
    for src, dst, _ in SCREENS:
        p = os.path.join(DUMP, src)
        if os.path.exists(p):
            shutil.copyfile(p, os.path.join(OUT, dst))
            n += 1
        else:
            print("  [skip] 缺少", src)
    return n


# ----------------------------------------------------------------------------
# 2) 顶部横幅
# ----------------------------------------------------------------------------
def make_banner():
    W, H = 1360, 420
    c = gradient(W, H, (13, 22, 31), (6, 10, 15)).convert("RGBA")
    grid(c, 34)
    # 左侧色带
    d = ImageDraw.Draw(c)
    d.rectangle([0, 0, 6, H], fill=CYAN)
    d.rectangle([6, 0, 9, H], fill=ACCENT)

    f_latin = font(FONT_BOLD, 42)
    f_cjk = font(FONT_BOLD, 58)
    f_tag = font(FONT_REG, 21)
    f_badge = font(FONT_BOLD, 17)
    f_author = font(FONT_REG, 18)

    d = ImageDraw.Draw(c)
    x = 54
    text(d, (x, 74), "JiezhiMod", f_latin, CYAN, shadow=(0, 0, 0, 160))
    text(d, (x, 122), "节枝特效工具", f_cjk, TXT, shadow=(0, 0, 0, 160))
    text(d, (x, 208), "Minecraft Forge 1.12.2 · 视觉特效 / 指令工具包", f_tag, DIM)

    badges = [("MC 1.12.2", CYAN), ("Forge 14.23.5.2864", OKC), ("v3.4", ACCENT), ("客户端 + 服务端", WARN)]
    bx = x
    for s, col in badges:
        w = text_w(d, s, f_badge) + 26
        panel(c, [bx, 250, bx + w, 250 + 34], radius=17,
              fill=(col[0], col[1], col[2], 26), outline=(col[0], col[1], col[2], 120))
        d = ImageDraw.Draw(c)
        text(d, (bx + w / 2, 250 + 17), s, f_badge, col, anchor="mm")
        bx += w + 10

    d = ImageDraw.Draw(c)
    text(d, (x, 318), "作者  树上的节枝 · B站 space.bilibili.com/3494363348863838", f_author, DIM)
    text(d, (x, 348), "视角抖动 / 白屏震撼弹 / 字幕 / 倒计时 / 通知 / 线圈 / 红石延时 / 喷气背包 / DFC 显示器",
         font(FONT_REG, 16), (96, 118, 134))

    # 右侧三张屏幕缩略图
    shots = ["dfc-main.png", "dfc-components.png", "dfc-custom.png"]
    sw, sh = 234, 176          # 4:3
    gap = 9
    total = sw * len(shots) + gap * (len(shots) - 1)
    sx = W - 46 - total
    sy = (H - sh) // 2
    for i, name in enumerate(shots):
        p = os.path.join(OUT, name)
        if not os.path.exists(p):
            continue
        img = Image.open(p).convert("RGB").resize((sw, sh), Image.LANCZOS)
        bx = sx + i * (sw + gap)
        # 外发光 + 边框
        glow = Image.new("RGBA", (sw + 16, sh + 16), (0, 0, 0, 0))
        ImageDraw.Draw(glow).rounded_rectangle([0, 0, sw + 15, sh + 15], radius=8,
                                               fill=(CYAN[0], CYAN[1], CYAN[2], 40))
        c.alpha_composite(glow, (bx - 8, sy - 8))
        c.alpha_composite(img.convert("RGBA"), (bx, sy))
        ImageDraw.Draw(c).rounded_rectangle([bx - 1, sy - 1, bx + sw, sy + sh], radius=3,
                                            outline=(CYAN[0], CYAN[1], CYAN[2], 200), width=2)

    c.convert("RGB").save(os.path.join(OUT, "banner.png"))
    return "banner.png"


# ----------------------------------------------------------------------------
# 3) 物品一览（等距视角不需要，物品就是 16x16 图标放大）
# ----------------------------------------------------------------------------
ITEMS = [
    ("speed_coil.png",   "加速线圈",     "移速 ×5",        "jiezhimod:speed_coil"),
    ("gravity_coil.png", "重力线圈",     "跳跃约 6 格",     "jiezhimod:gravity_coil"),
    ("healing_coil.png", "治愈线圈",     "每秒 +4 生命",    "jiezhimod:healing_coil"),
    ("jetpack.png",      "喷气背包",     "胸甲槽 · 飞行",   "jiezhimod:jetpack"),
    ("jet_switch.png",   "喷气开关",     "手持 · 右键起飞",  "jiezhimod:jet_switch"),
    ("dfc_remote.png",   "DFC 遥控器",   "绑定 / 记录设备",  "jiezhimod:dfc_remote"),
    ("dfc_screen.png",   "DFC 监控显示器", "4×3 吊装 · 核心页", "jiezhimod:dfc_screen"),
    ("dfc_monitor.png",  "DFC 部件监视器", "4×3 吊装 · 部件页", "jiezhimod:dfc_monitor"),
    ("dfc_custom.png",   "DFC 自定义显示屏", "4×3 吊装 · 8 条数据", "jiezhimod:dfc_custom"),
]


def make_items():
    cols = 3
    cw, ch = 268, 258
    pad = 34
    head = 126                      # 标题(28) + 副标题(80) + 分隔线(110) 之后再开始排格子
    rows = (len(ITEMS) + cols - 1) // cols
    W = pad * 2 + cw * cols
    H = head + rows * ch + pad
    c = gradient(W, H, (13, 22, 31), (6, 10, 15)).convert("RGBA")
    grid(c, 34)

    d = ImageDraw.Draw(c)
    text(d, (pad, 26), "物品一览", font(FONT_BOLD, 40), TXT)
    text(d, (pad, 80), "全部在创造物品栏「jiezhimod物品」页 · 线圈均不用药水效果实现，可与药水叠加",
         font(FONT_REG, 17), DIM)
    d.line([(pad, 111), (W - pad, 111)], fill=(255, 255, 255, 30))

    for i, (tex, name, desc, ident) in enumerate(ITEMS):
        r, cc = divmod(i, cols)
        x0 = pad + cc * cw
        y0 = head + r * ch
        box = [x0 + 6, y0 + 6, x0 + cw - 6, y0 + ch - 6]
        panel(c, box, radius=14, fill=(255, 255, 255, 9), outline=(255, 255, 255, 24))
        d = ImageDraw.Draw(c)

        p = os.path.join(TEX, "items", tex)
        if os.path.exists(p):
            ic = Image.open(p).convert("RGBA").resize((112, 112), Image.NEAREST)
            # 图标底衬，避免深色贴图糊在深色背景上
            plate = [box[0] + 28, box[1] + 22, box[0] + 28 + 112, box[1] + 22 + 112]
            d.rounded_rectangle(plate, radius=8, fill=(255, 255, 255, 8))
            c.alpha_composite(ic, (plate[0], plate[1]))

        cx = box[0] + (box[2] - box[0]) // 2
        f_name = fit_text(d, name, FONT_BOLD, 23, box[2] - box[0] - 16, 15)
        text(d, (cx, box[1] + 150), name, f_name, TXT, anchor="ma")
        text(d, (cx, box[1] + 182), desc, font(FONT_REG, 17), CYAN, anchor="ma")
        f_id = fit_text(d, ident, FONT_MONO, 14, box[2] - box[0] - 16, 11)
        text(d, (cx, box[1] + 208), ident, f_id, (104, 126, 142), anchor="ma")

    c.convert("RGB").save(os.path.join(OUT, "items.png"))
    return "items.png"


# ----------------------------------------------------------------------------
# 4) 等距方块（红石延时器 关 / 开）
# ----------------------------------------------------------------------------
def sample(tex, u, v, shade):
    s = tex.width
    x = min(s - 1, max(0, int(u * s)))
    y = min(s - 1, max(0, int(v * s)))
    r, g, b, a = tex.getpixel((x, y))
    return (int(r * shade), int(g * shade), int(b * shade), a)


def iso_cube(tex_top, tex_left, tex_right, w=104, h=52, dh=96):
    """把三张贴图拼成一个等距立方体，返回 RGBA 图（宽 2w、高 2h+dh）"""
    W, H = 2 * w, 2 * h + dh
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    px = out.load()
    cx, cy = w, h          # 顶面中心

    for y in range(H):
        for x in range(W):
            # --- 顶面：菱形 ---
            X = (x - cx) / w
            Y = (y - cy + h) / h
            u = (X + Y) / 2.0
            v = (Y - X) / 2.0
            if 0.0 <= u < 1.0 and 0.0 <= v < 1.0:
                px[x, y] = sample(tex_top, u, v, 1.0)
                continue
            # --- 左面 ---
            if 0 <= y - cy <= dh:
                u2 = (x - cx + w) / w
                if 0.0 <= u2 < 1.0:
                    v2 = (y - cy - u2 * h) / dh
                    if 0.0 <= v2 < 1.0:
                        px[x, y] = sample(tex_left, u2, v2, 0.76)
                        continue
            # --- 右面 ---
            if 0 <= y - cy <= dh:
                u3 = (x - cx) / w
                if 0.0 <= u3 < 1.0:
                    v3 = (y - cy - h + u3 * h) / dh
                    if 0.0 <= v3 < 1.0:
                        px[x, y] = sample(tex_right, u3, v3, 0.58)
                        continue
    return out


def make_blocks():
    w, h, dh = 104, 52, 96
    cw, chh = 320, 300
    pad = 34
    head = 126
    W = pad * 2 + cw * 2
    H = head + chh + pad
    c = gradient(W, H, (13, 22, 31), (6, 10, 15)).convert("RGBA")
    grid(c, 34)

    d = ImageDraw.Draw(c)
    text(d, (pad, 26), "方块一览", font(FONT_BOLD, 40), TXT)
    text(d, (pad, 80), "红石延时器：把红石信号整体延后输出（上升沿 / 下降沿都延时，短脉冲不会被吞）",
         font(FONT_REG, 17), DIM)
    d.line([(pad, 111), (W - pad, 111)], fill=(255, 255, 255, 30))

    variants = [
        ("delay_top.png", "delay_side.png", "delay_side.png", "关  OFF", DIM),
        ("delay_top_on.png", "delay_side_on.png", "delay_side_on.png", "开  ON", OKC),
    ]
    for i, (t, l, r, label, col) in enumerate(variants):
        x0 = pad + i * cw
        box = [x0 + 6, head + 6, x0 + cw - 6, head + chh - 6]
        panel(c, box, radius=14, fill=(255, 255, 255, 9), outline=(255, 255, 255, 24))
        d = ImageDraw.Draw(c)

        try:
            cube = iso_cube(Image.open(os.path.join(TEX, "blocks", t)).convert("RGBA"),
                            Image.open(os.path.join(TEX, "blocks", l)).convert("RGBA"),
                            Image.open(os.path.join(TEX, "blocks", r)).convert("RGBA"),
                            w, h, dh)
            cx = box[0] + (box[2] - box[0]) // 2 - cube.width // 2
            c.alpha_composite(cube, (cx, box[1] + 26))
        except Exception as e:      # 贴图缺失时不要让整张图挂掉
            print("  [warn] 方块贴图失败:", e)

        d = ImageDraw.Draw(c)
        cxm = box[0] + (box[2] - box[0]) // 2
        text(d, (cxm, box[1] + 222), "红石延时器 " + label, font(FONT_BOLD, 24), col, anchor="ma")
        text(d, (cxm, box[1] + 256), "jiezhimod:delay_block", font(FONT_MONO, 15), (104, 126, 142), anchor="ma")

    c.convert("RGB").save(os.path.join(OUT, "blocks.png"))
    return "blocks.png"


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    n = collect_screens()
    print("屏幕截图 %d 张 -> screenshots/" % n)
    for fn in (make_banner, make_items, make_blocks):
        print("生成", fn())
    print("全部完成 ->", OUT)
