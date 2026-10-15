#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成喷漆背包 / 喷气开关 / 喷气粒子的贴图（纯标准库，不依赖 PIL）。

产出：
  items/jetpack.png         64x64 喷漆背包（金属罐体 + 双喷嘴 + 顶部把手）
  items/jet_switch.png      64x64 喷气开关（握把 + 红色按钮 + 喷口）
  particles/jet_exhaust.png 16x16 喷气粒子（软边圆点，中心白、边缘橙红）

设计约束（都是踩过坑之后定下来的）：
  - **物品贴图必须自带 alpha**。物品渲染走的是"不透明贴图"管线，
    不写 alpha 通道就会出现整块方形底色（之前线圈贴图就踩过）。
  - **描边要比主体暗一档**（Minecraft 物品在游戏里会被环境光压暗），
    太接近的对比度在阴影里会糊成一团。
  - 主体色选中高饱和的金属灰蓝 + 橙色喷口，和现有线圈的红/紫区分开。

用法：python tools/gen_jetpack_textures.py [--preview]
"""
import math
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ITEMS = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod", "textures", "items")
PARTS = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod", "textures", "particles")

CLEAR = (0, 0, 0, 0)

# 喷漆背包：金属罐体
BODY = (96, 106, 122, 255)
BODY_HI = (132, 144, 162, 255)
BODY_LO = (62, 70, 84, 255)
EDGE = (38, 44, 56, 255)

# 喷口 / 按钮：橙色系
NOZZLE = (222, 116, 42, 255)
NOZZLE_HI = (250, 168, 84, 255)
NOZZLE_LO = (150, 70, 22, 255)

# 把手 / 高光件
TRIM = (176, 186, 200, 255)

# 开关握把
GRIP = (58, 52, 48, 255)
GRIP_HI = (88, 80, 74, 255)
BUTTON = (206, 58, 46, 255)
BUTTON_HI = (246, 116, 96, 255)


def blank(size, color=CLEAR):
    return [[color for _ in range(size)] for _ in range(size)]


def rect(px, x0, y0, x1, y1, color):
    """闭区间矩形，带边界裁剪"""
    h = len(px)
    w = len(px[0])
    for y in range(max(0, y0), min(h, y1 + 1)):
        for x in range(max(0, x0), min(w, x1 + 1)):
            px[y][x] = color


def rrect(px, x0, y0, x1, y1, r, color):
    """圆角矩形（1.12.2 物品贴图没有圆角，必须自己画）"""
    h = len(px)
    w = len(px[0])
    for y in range(max(0, y0), min(h, y1 + 1)):
        for x in range(max(0, x0), min(w, x1 + 1)):
            # 角落做圆角剔除
            cx = None
            cy = None
            if x < x0 + r and y < y0 + r:
                cx, cy = x0 + r, y0 + r
            elif x > x1 - r and y < y0 + r:
                cx, cy = x1 - r, y0 + r
            elif x < x0 + r and y > y1 - r:
                cx, cy = x0 + r, y1 - r
            elif x > x1 - r and y > y1 - r:
                cx, cy = x1 - r, y1 - r
            if cx is not None:
                if math.hypot(x - cx, y - cy) > r:
                    continue
            px[y][x] = color


def hline(px, x0, x1, y, color):
    rect(px, x0, y, x1, y, color)


def vline(px, x, y0, y1, color):
    rect(px, x, y0, x, y1, color)


def build_jetpack():
    """喷漆背包：双喷罐 + 顶部提手 + 底部两个喷口"""
    px = blank(64)

    # 顶部提手（先画，会被罐体盖住下沿）
    rrect(px, 22, 6, 41, 16, 4, EDGE)
    rrect(px, 24, 8, 39, 15, 3, TRIM)
    rrect(px, 27, 10, 36, 13, 2, CLEAR)   # 提手空腔
    rect(px, 24, 8, 39, 9, TRIM)          # 空腔上沿的把手横梁

    # 左侧喷罐
    rrect(px, 10, 14, 29, 47, 6, EDGE)
    rrect(px, 12, 16, 27, 45, 5, BODY)
    rect(px, 14, 18, 25, 20, BODY_HI)     # 顶部高光
    vline(px, 14, 22, 42, BODY_HI)         # 左侧受光边
    vline(px, 25, 22, 43, BODY_LO)         # 右侧背光边

    # 右侧喷罐
    rrect(px, 34, 14, 53, 47, 6, EDGE)
    rrect(px, 36, 16, 51, 45, 5, BODY)
    rect(px, 38, 18, 49, 20, BODY_HI)
    vline(px, 38, 22, 42, BODY_HI)
    vline(px, 49, 22, 43, BODY_LO)

    # 中间连接带
    rrect(px, 28, 24, 35, 40, 3, EDGE)
    rrect(px, 30, 26, 33, 38, 2, BODY_LO)
    hline(px, 29, 34, 30, TRIM)

    # 底部两个喷口（朝下，这是"往下喷气"的视觉来源）
    for cx in (19, 48):
        rrect(px, cx - 5, 46, cx + 5, 55, 3, NOZZLE_LO)
        rrect(px, cx - 4, 47, cx + 4, 54, 3, NOZZLE)
        rect(px, cx - 2, 49, cx + 1, 52, NOZZLE_HI)
        # 喷口内芯
        rect(px, cx - 2, 54, cx + 2, 57, NOZZLE_LO)
        rect(px, cx - 1, 55, cx + 1, 57, (90, 40, 16, 255))

    # 顶部小气阀
    rrect(px, 30, 18, 33, 24, 1, TRIM)

    return px


def build_switch():
    """喷气开关：手柄 + 拇指红按钮 + 前端喷口"""
    px = blank(64)

    # 手柄（略带斜握持感）
    rrect(px, 20, 26, 43, 56, 7, EDGE)
    rrect(px, 22, 28, 41, 54, 6, GRIP)
    # 防滑纹
    for y in range(33, 52, 4):
        hline(px, 25, 38, y, GRIP_HI)

    # 顶部面板
    rrect(px, 22, 12, 41, 30, 6, EDGE)
    rrect(px, 24, 14, 39, 28, 5, TRIM)
    rrect(px, 25, 15, 38, 27, 4, BODY)
    hline(px, 26, 37, 16, BODY_HI)

    # 红色拇指按钮
    rrect(px, 27, 8, 36, 18, 4, EDGE)
    rrect(px, 28, 9, 35, 17, 3, BUTTON)
    rrect(px, 29, 10, 33, 13, 2, BUTTON_HI)

    # 前端喷口（朝前下方）
    rrect(px, 26, 54, 37, 60, 3, NOZZLE_LO)
    rrect(px, 27, 55, 36, 59, 2, NOZZLE)
    rect(px, 29, 58, 34, 60, (110, 48, 18, 255))

    # 侧面散热片
    for y in (30, 36, 42):
        hline(px, 22, 25, y, NOZZLE)
        hline(px, 38, 41, y, NOZZLE)

    return px


def build_particle():
    """喷气粒子：中心亮白、向外过渡到橙红，边缘全透明（软边）"""
    px = blank(16)
    c = 7.5
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - c, y - c)
            # 半径给到 7.9，让 16x16 里几乎填满：
            # 粒子贴图在游戏里会被缩得很小，边缘留太多透明会让它"看起来很淡"
            if d > 7.9:
                px[y][x] = CLEAR
                continue
            t = d / 7.9                      # 0 中心 -> 1 边缘
            if t < 0.40:
                k = t / 0.40
                r = 255
                g = int(255 - 26 * k)
                b = int(255 - 80 * k)
            else:
                k = (t - 0.40) / 0.60
                r = int(255 - 8 * k)
                g = int(229 - 128 * k)
                b = int(175 - 132 * k)
            # 边缘 alpha 渐隐（最后 25% 快速收掉，得到"软边"观感）
            if t < 0.75:
                a = 255
            else:
                a = int(255 * (1.0 - (t - 0.75) / 0.25))
            if a <= 0:
                px[y][x] = CLEAR
            else:
                px[y][x] = (r, g, b, a)
    return px


def write_png(path, px):
    size = len(px)
    raw = bytearray()
    for y in range(size):
        raw.append(0)          # filter type 0 (None)
        for x in range(size):
            raw.extend(px[y][x])

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as fh:
        fh.write(png)


def ascii_preview(px, step=4):
    ramp = " .:-=+*#%@"
    out = []
    for y in range(0, len(px), step):
        line = ""
        for x in range(0, len(px), step):
            r, g, b, a = px[y][x]
            if a < 40:
                line += " "
            else:
                lum = (r * 0.3 + g * 0.59 + b * 0.11) / 255.0
                line += ramp[min(9, int(lum * 9.99))]
        out.append(line)
    return "\n".join(out)


def main():
    preview = "--preview" in sys.argv
    os.makedirs(ITEMS, exist_ok=True)
    os.makedirs(PARTS, exist_ok=True)

    jobs = [
        (os.path.join(ITEMS, "jetpack.png"), build_jetpack()),
        (os.path.join(ITEMS, "jet_switch.png"), build_switch()),
        (os.path.join(PARTS, "jet_exhaust.png"), build_particle()),
    ]
    for path, px in jobs:
        write_png(path, px)
        print("wrote: %s (%dx%d)" % (path, len(px), len(px)))
        if preview:
            print(ascii_preview(px))
            print()


if __name__ == "__main__":
    main()
