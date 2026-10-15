#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成红石延时器的 16x16 贴图（纯标准库，不依赖 PIL）。

产出 4 张：
  delay_side / delay_top        —— 未输出（暗）
  delay_side_on / delay_top_on  —— 正在输出（红亮）

方块不做朝向（输出方向以信号进入面为基准），所以贴图不带方向箭头：
  top  = 石板面板 + 一枚"时钟"（指针 + 表盘），一眼看出这是"时间/延时"装置
  side = 带横向散热纹的深灰石板

用法：python tools/gen_block_textures.py [--preview]
"""
import math
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod", "textures", "blocks")

# 底色必须是"一眼看得见的中灰"。之前用的是 RGB(59,59,69) 近黑色，
# 在夜里或普通光照下和阴影几乎融为一体，玩家会误以为方块是透明的 / 是个洞。
BASE = (124, 124, 134, 255)
BASE_HI = (146, 146, 158, 255)
EDGE = (78, 78, 88, 255)
BASE_ON = (140, 112, 112, 255)
BASE_ON_HI = (166, 132, 132, 255)
EDGE_ON = (104, 62, 62, 255)
HAND_OFF = (198, 62, 62, 255)
HAND_ON = (255, 92, 92, 255)
RING_OFF = (140, 58, 58, 255)
RING_ON = (216, 84, 84, 255)


def blank(base):
    return [[base for _ in range(16)] for _ in range(16)]


def border(px, color):
    for i in range(16):
        px[0][i] = color
        px[15][i] = color
        px[i][0] = color
        px[i][15] = color


def speckle(px, color):
    for (x, y) in ((2, 3), (11, 2), (4, 12), (13, 9), (12, 13), (13, 13)):
        px[y][x] = color


# 表盘：圆心 (7, 7)、半径 5 的圆环；指针 12 点方向 + 3 点方向
CLOCK = (
    (7, 3), (7, 4), (7, 5), (7, 6),  # 12 点指针：竖直向上
    (8, 7), (9, 7), (10, 7),         # 3 点指针：水平向右
)


def clock_ring():
    """圆环像素集合（半径 5，圆心 7,7，16x16 内取最接近的一圈）"""
    pts = set()
    for y in range(16):
        for x in range(16):
            # 圆心 7.5,7.5（像素中心），上下左右留白对称
            d = math.hypot(x - 7.5, y - 7.5)
            if 4.1 <= d <= 5.6:
                pts.add((x, y))
    # 去掉压到外边框的像素，避免和石板边线打架
    return set((x, y) for (x, y) in pts if 1 <= x <= 14 and 1 <= y <= 14)


def build_side(on):
    px = blank(BASE_ON if on else BASE)
    border(px, EDGE_ON if on else EDGE)
    speckle(px, BASE_ON_HI if on else BASE_HI)
    for x in range(1, 15):
        px[8][x] = EDGE_ON if on else EDGE
    return px


def build_top(on):
    px = build_side(on)
    ring = RING_ON if on else RING_OFF
    hand = HAND_ON if on else HAND_OFF
    for (x, y) in clock_ring():
        px[y][x] = ring
    for (x, y) in CLOCK:
        px[y][x] = hand
    return px


def write_png(path, px):
    raw = bytearray()
    for y in range(16):
        raw.append(0)
        for x in range(16):
            raw.extend(px[y][x])

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as fh:
        fh.write(png)


def ascii_preview(px):
    out = []
    for y in range(16):
        line = ""
        for x in range(16):
            r, g, b, a = px[y][x]
            if r > 240 and g < 120:
                line += "#"      # 指针（亮红）
            elif r > 120 and g < 100:
                line += "o"      # 表盘 / 边框
            elif r > 80:
                line += "."
            else:
                line += " "
        out.append(line)
    return "\n".join(out)


def main():
    if not os.path.isdir(OUT_DIR):
        os.makedirs(OUT_DIR)
    files = {
        "delay_side.png": build_side(False),
        "delay_top.png": build_top(False),
        "delay_side_on.png": build_side(True),
        "delay_top_on.png": build_top(True),
    }
    for name, px in files.items():
        path = os.path.join(OUT_DIR, name)
        write_png(path, px)
        print("wrote %s (%d bytes)" % (name, os.path.getsize(path)))
    # 旧的方向性贴图已作废，删掉免得被打进 jar
    for stale in ("delay_front.png", "delay_front_on.png"):
        p = os.path.join(OUT_DIR, stale)
        if os.path.exists(p):
            os.remove(p)
            print("removed %s" % stale)

    import sys
    if "--preview" in sys.argv:
        print("\n=== delay_top_on.png ===")
        print(ascii_preview(files["delay_top_on.png"]))
        print("\n=== delay_side_on.png ===")
        print(ascii_preview(files["delay_side_on.png"]))


if __name__ == "__main__":
    main()
