#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen_dfc_assets.py —— 生成 DFC 显示器相关资源（纯标准库，无需 Pillow）。

产出：
  1. textures/items/dfc_screen.png   显示器物品图标（64x64）
  2. textures/items/dfc_remote.png   遥控器物品图标（64x64）
  3. blockstates/dfc_screen.json     4(part) x 12(facing) = 48 个变体
  4. models/block/dfc_screen.json    空模型（几何全部由 TESR 画，见 RenderDfcScreen）

用法：
  python tools/gen_dfc_assets.py            # 生成
  python tools/gen_dfc_assets.py --preview  # 生成并在终端打印 ASCII 预览
"""
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod")

CLEAR = (0, 0, 0, 0)


# ==================== PNG ====================

def blank(size):
    return [[CLEAR for _ in range(size)] for _ in range(size)]


def write_png(path, px, w, h):
    raw = bytearray()
    for y in range(h):
        raw.append(0)   # filter type 0
        for x in range(w):
            r, g, b, a = px[y][x]
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    out += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(out)


# ==================== 绘制原语 ====================

def rect(px, x0, y0, x1, y1, color):
    for y in range(max(0, y0), min(len(px), y1)):
        for x in range(max(0, x0), min(len(px), x1)):
            px[y][x] = color


def frame(px, x0, y0, x1, y1, color):
    rect(px, x0, y0, x1, y0 + 1, color)
    rect(px, x0, y1 - 1, x1, y1, color)
    rect(px, x0, y0, x0 + 1, y1, color)
    rect(px, x1 - 1, y0, x1, y1, color)


def dot(px, cx, cy, r, color):
    for y in range(cy - r, cy + r + 1):
        for x in range(cx - r, cx + r + 1):
            if 0 <= x < len(px) and 0 <= y < len(px):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    px[y][x] = color


def ring(px, cx, cy, r, color, thick=1):
    """空心圆：给中间的仪表盘用"""
    for y in range(cy - r - 1, cy + r + 2):
        for x in range(cx - r - 1, cx + r + 2):
            if 0 <= x < len(px) and 0 <= y < len(px):
                d2 = (x - cx) ** 2 + (y - cy) ** 2
                if (r - thick) ** 2 < d2 <= r * r:
                    px[y][x] = color


# ==================== 图标 ====================

def build_screen():
    """显示器：金属外壳 + 深色液晶屏 + 三栏数据的暗示"""
    px = blank(64)

    SHELL = (74, 82, 90, 255)
    SHELL_D = (44, 49, 55, 255)
    GLASS = (6, 10, 16, 255)
    CYAN = (72, 214, 255, 255)
    AMBER = (255, 138, 61, 255)
    GREEN = (82, 224, 138, 255)

    # 支架（先画，被机身压住上半截）
    rect(px, 30, 44, 34, 54, SHELL_D)
    rect(px, 24, 54, 40, 58, SHELL)
    rect(px, 24, 54, 40, 55, (92, 100, 108, 255))

    # 机身 + 内衬
    rect(px, 6, 8, 58, 46, SHELL)
    rect(px, 8, 10, 56, 44, SHELL_D)
    rect(px, 10, 12, 54, 42, GLASS)

    # 左栏：温度（琥珀色块 + 两行短条）
    rect(px, 13, 15, 23, 23, AMBER)
    rect(px, 13, 26, 25, 28, (255, 200, 90, 220))
    rect(px, 13, 30, 20, 32, (150, 160, 170, 200))

    # 中栏：仪表圆环
    ring(px, 37, 24, 8, (60, 120, 150, 255), 2)
    dot(px, 37, 24, 4, (24, 60, 88, 255))
    dot(px, 37, 24, 2, CYAN)
    rect(px, 31, 35, 43, 37, (120, 135, 150, 220))

    # 右栏：日志线
    for i, col in enumerate((GREEN, (170, 180, 190, 210), (150, 120, 90, 210))):
        y = 16 + i * 5
        rect(px, 47, y, 52 - i, y + 2, col)

    frame(px, 6, 8, 58, 46, (96, 104, 112, 255))
    return px


def build_remote():
    """遥控器：机身 + 小屏 + 按钮阵列 + 天线"""
    px = blank(64)

    BODY = (46, 52, 57, 255)
    BODY_D = (32, 37, 42, 255)
    PANEL = (8, 26, 22, 255)
    GREEN = (82, 224, 138, 255)
    CYAN = (72, 214, 255, 255)
    AMBER = (255, 193, 61, 255)

    # 天线
    rect(px, 30, 2, 34, 10, BODY_D)
    dot(px, 32, 4, 3, (120, 130, 140, 255))

    # 机身
    rect(px, 21, 8, 43, 58, BODY)
    rect(px, 23, 10, 41, 56, BODY_D)

    # 屏幕
    rect(px, 25, 13, 39, 27, PANEL)
    frame(px, 25, 13, 39, 27, (60, 70, 76, 255))
    rect(px, 27, 16, 37, 18, GREEN)
    rect(px, 27, 20, 33, 22, (60, 160, 110, 220))
    rect(px, 35, 20, 37, 22, AMBER)

    # 按钮：2x2 圆钮 + 下方长条
    for (bx, by, col) in ((29, 32, CYAN), (35, 32, (150, 160, 170, 255)),
                          (29, 38, (255, 90, 90, 255)), (35, 38, (150, 160, 170, 255))):
        dot(px, bx, by, 2, col)
    rect(px, 27, 44, 37, 50, (58, 66, 72, 255))
    rect(px, 28, 45, 36, 49, CYAN)

    return px


def build_monitor():
    """
    部件监视器图标：同一台机身，但屏幕里是**六个部件仪表块**（3 列 x 2 行），
    而不是核心屏那套"温度大数字 + 圆盘 + 日志"，一眼能区分两块屏。
    """
    px = blank(64)

    SHELL = (74, 82, 90, 255)
    SHELL_D = (44, 49, 55, 255)
    GLASS = (6, 10, 16, 255)
    CYAN = (72, 214, 255, 255)
    AMBER = (255, 193, 61, 255)
    GREEN = (82, 224, 138, 255)

    # 支架（先画，被机身压住上半截）
    rect(px, 30, 44, 34, 54, SHELL_D)
    rect(px, 24, 54, 40, 58, SHELL)
    rect(px, 24, 54, 40, 55, (92, 100, 108, 255))

    # 机身 + 内衬
    rect(px, 6, 8, 58, 46, SHELL)
    rect(px, 8, 10, 56, 44, SHELL_D)
    rect(px, 10, 12, 54, 42, GLASS)

    # 六个部件块：左栏绿（发射器/接收器）、中栏青（燃料/喷射器）、右栏琥珀（能量/爆发）
    cols = ((13, 23, GREEN), (27, 37, CYAN), (41, 51, AMBER))
    rows = ((15, 26), (30, 41))
    for (x0, x1, _col) in cols:
        for (y0, y1) in rows:
            rect(px, x0, y0, x1, y1, (18, 30, 40, 255))
            frame(px, x0, y0, x1, y1, (52, 66, 78, 255))
    # 每个块里的一条"读数条"，颜色代表它测的东西
    for i, (x0, x1, col) in enumerate(cols):
        rect(px, x0 + 2, 18, x1 - 3 - i, 20, col)
        rect(px, x0 + 2, 33, x1 - 4 + i, 35, col)
        rect(px, x0 + 2, 22, x1 - 3, 23, (70, 84, 96, 200))
        rect(px, x0 + 2, 37, x1 - 3, 38, (70, 84, 96, 200))

    frame(px, 6, 8, 58, 46, (96, 104, 112, 255))
    return px


def build_custom():
    """
    自定义显示屏图标：同一台机身，屏幕里是**8 行数据条**（每行 = 一台设备的一项读数），
    正是这块屏在主界面上画的东西。三块屏的图标因此一眼可分：
      核心屏   -> 大数字 + 圆盘 + 日志行
      部件监视 -> 3x2 六个部件块
      自定义屏 -> 8 行"设备 + 读数条"
    """
    px = blank(64)

    SHELL = (74, 82, 90, 255)
    SHELL_D = (44, 49, 55, 255)
    GLASS = (6, 10, 16, 255)
    ROW_BG = (18, 30, 40, 255)
    COLORS = [
        (82, 224, 138, 255),    # 绿：流体类
        (72, 214, 255, 255),    # 青：容量类
        (255, 193, 61, 255),    # 琥珀：能量类
        (255, 107, 214, 255),   # 品红：其它
    ]

    # 支架（先画，被机身压住上半截）
    rect(px, 30, 44, 34, 54, SHELL_D)
    rect(px, 24, 54, 40, 58, SHELL)
    rect(px, 24, 54, 40, 55, (92, 100, 108, 255))

    # 机身 + 内衬
    rect(px, 6, 8, 58, 46, SHELL)
    rect(px, 8, 10, 56, 44, SHELL_D)
    rect(px, 10, 12, 54, 42, GLASS)

    # 8 行数据条：行高 3px（12 + 8*3 = 36，正好落进玻璃区 12..42）
    # 每行结构：1px 设备色标 + 底槽 + 长短不一的读数条
    lengths = (26, 18, 31, 22, 14, 28, 20, 24)
    for i in range(8):
        y0 = 14 + i * 3
        rect(px, 12, y0, 53, y0 + 2, ROW_BG)
        col = COLORS[i % len(COLORS)]
        # 设备色标
        rect(px, 12, y0, 14, y0 + 2, col)
        # 读数条（长短代表液位/电量，越往下越短看起来更自然）
        rect(px, 15, y0, 15 + lengths[i], y0 + 2, col)

    frame(px, 6, 8, 58, 46, (96, 104, 112, 255))
    return px


# ==================== 模型 / 方块状态 ====================
def write_blockstate():
    """
    每个方块 4 个朝向变体（meta 0~3）。

    【为什么不是 12(part) x 4(facing) = 48 个变体】
    1.12.2 的 Block 内部有几个**按 meta 索引的定长 16 数组**
    （harvestTool / harvestLevel …），状态数一旦超过 16，
    setHarvestLevel 就会在越界索引上直接抛 ArrayIndexOutOfBounds。
    结构位置因此不进方块状态，改由几何推导（见 ScreenMultiblock）。

    dfc_monitor 与 dfc_screen 共用同一个空模型 dfc_screen_empty ——
    几何整个由 TESR 画，方块模型只提供破坏粒子贴图。

    【model 引用**不能**带 "block/" 前缀】
    原版 Variant.Deserializer.getResourceLocationBlock() 是无条件拼 "block/" 的：
        new ResourceLocation(domain, "block/" + rl.getResourcePath())
    写成 "jiezhimod:block/dfc_screen_empty" 会被解析成 models/block/block/...
    → 找不到文件 → 日志刷 "Unable to load model"。
    这几个方块是 getRenderType()=INVISIBLE（整块交给 TESR），漏模型不会显示紫黑块，
    所以这个坑会**静默**地一直存在 —— 只有这条注释和自测断言拦得住。
    """
    # EnumFacing.getHorizontal(i): 0=SOUTH, 1=WEST, 2=NORTH, 3=EAST
    order = ["south", "west", "north", "east"]
    paths = []
    for name in ("dfc_screen", "dfc_screen_part", "dfc_monitor", "dfc_custom"):
        entries = ['    "facing=%s": { "model": "jiezhimod:dfc_screen_empty" }' % d
                   for d in order]
        body = "{\n  \"variants\": {\n" + ",\n".join(entries) + "\n  }\n}\n"
        path = os.path.join(ASSETS, "blockstates", name + ".json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fp:
            fp.write(body)
        paths.append(path)
    return paths


def write_item_model(name):
    """物品形态模型：直接引用 items/<name>.png"""
    import json
    model = {
        "parent": "item/generated",
        "textures": {"layer0": "jiezhimod:items/" + name}
    }
    path = os.path.join(ASSETS, "models", "item", name + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(model, fp, indent=2, ensure_ascii=False)
        fp.write("\n")
    return path


def write_block_model():
    """空 elements：世界里的一切都由 TESR 画，模型只提供破坏粒子用的贴图"""
    import json
    model = {
        "textures": {"particle": "jiezhimod:items/dfc_screen"},
        "elements": []
    }
    path = os.path.join(ASSETS, "models", "block", "dfc_screen_empty.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(model, fp, indent=2, ensure_ascii=False)
    # 上一版留下的 48 变体空模型，清掉避免误导
    stale = os.path.join(ASSETS, "models", "block", "dfc_screen.json")
    if os.path.exists(stale):
        os.remove(stale)
        print("removed stale", os.path.relpath(stale, ROOT))
    return path


# ==================== 预览 ====================

RAMP = " .:-=+*#%@"


def preview(px, title):
    print("=" * 8, title, "=" * 8)
    step = 2
    for y in range(0, len(px), step):
        row = []
        for x in range(0, len(px[0]), step):
            r, g, b, a = px[y][x]
            if a < 40:
                row.append(" ")
            else:
                lum = (r * 299 + g * 587 + b * 114) // 1000
                row.append(RAMP[min(len(RAMP) - 1, lum * len(RAMP) // 256)])
        print("".join(row))
    print()


def main():
    show = "--preview" in sys.argv

    screen = build_screen()
    remote = build_remote()
    monitor = build_monitor()
    custom = build_custom()
    write_png(os.path.join(ASSETS, "textures", "items", "dfc_screen.png"), screen, 64, 64)
    write_png(os.path.join(ASSETS, "textures", "items", "dfc_remote.png"), remote, 64, 64)
    write_png(os.path.join(ASSETS, "textures", "items", "dfc_monitor.png"), monitor, 64, 64)
    write_png(os.path.join(ASSETS, "textures", "items", "dfc_custom.png"), custom, 64, 64)
    bs_paths = write_blockstate()
    bm = write_block_model()
    im_screen = write_item_model("dfc_screen")
    im_monitor = write_item_model("dfc_monitor")
    im_custom = write_item_model("dfc_custom")

    print("textures/items/dfc_screen.png")
    print("textures/items/dfc_remote.png")
    print("textures/items/dfc_monitor.png")
    print("textures/items/dfc_custom.png")
    for p in bs_paths:
        print(os.path.relpath(p, ROOT))
    print(os.path.relpath(bm, ROOT))
    print(os.path.relpath(im_screen, ROOT))
    print(os.path.relpath(im_monitor, ROOT))
    print(os.path.relpath(im_custom, ROOT))

    if show:
        preview(screen, "dfc_screen")
        preview(remote, "dfc_remote")
        preview(monitor, "dfc_monitor")
        preview(custom, "dfc_custom")


if __name__ == "__main__":
    main()
