# -*- coding: utf-8 -*-
"""
生成旋转警示灯 / 控制台 / 链接器的贴图（v4.5）。

【为什么这些贴图都"只是给物品栏和占位用"】
世界里那盏灯的外形是 TESR 现画的几何体（圆柱 / 圆罩 / 光锥），
16×16 的像素材质画不出"完美的圆"（只会得到一圈锯齿）。
所以这里只做三张：
  * alarm_light.png      —— 物品栏图标 + blockstates 占位模型（世界里 INVISIBLE）
  * alarm_console.png    —— 控制台的方块贴图（真用）
  * alarm_connector.png  —— 链接器的物品图标
"""
import io
import os
import math
import random

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
BLOCKS = os.path.join(HERE, os.pardir, "src", "main", "resources", "assets",
                      "jiezhimod", "textures", "blocks")
ITEMS = os.path.join(HERE, os.pardir, "src", "main", "resources", "assets",
                     "jiezhimod", "textures", "items")

S = 16


def metal_base(draw, base=0x33):
    """深色金属底 + 细噪点"""
    random.seed(7)
    for y in range(S):
        for x in range(S):
            v = base + random.randint(-6, 6)
            draw.point((x, y), fill=(v, v, v + 2, 255))


def make_light():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    metal_base(d)
    # 中心一圈发光的灯面（俯视看它是圆的）
    for r, col in ((6, (58, 66, 74, 255)), (5, (92, 104, 116, 255)),
                   (3, (150, 40, 40, 255)), (2, (210, 70, 70, 255))):
        d.ellipse([8 - r, 8 - r, 7 + r, 7 + r], fill=col)
    # 四角螺栓
    for (bx, by) in ((1, 1), (13, 1), (1, 13), (13, 13)):
        d.rectangle([bx, by, bx + 1, by + 1], fill=(70, 76, 84, 255))
    img.save(os.path.join(BLOCKS, "alarm_light.png"))
    print("alarm_light.png")


def make_console():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    metal_base(d, 0x28)
    # 面板凹槽
    d.rectangle([1, 1, 14, 14], outline=(16, 20, 24, 255))
    d.rectangle([2, 2, 13, 13], outline=(74, 84, 94, 255))
    # 小屏幕（暗青）
    d.rectangle([3, 3, 12, 8], fill=(14, 34, 38, 255))
    d.rectangle([3, 3, 12, 3], fill=(40, 90, 96, 255))
    # 三个指示灯：红 / 绿 / 黄
    for i, col in enumerate(((190, 50, 50), (60, 170, 90), (190, 170, 60))):
        cx = 5 + i * 3
        d.rectangle([cx, 10, cx + 1, 11], fill=col + (255,))
    # 底部一条警示斜纹
    for i in range(0, S, 3):
        d.line([(i, 15), (i + 3, 12)], fill=(190, 160, 40, 255))
    img.save(os.path.join(BLOCKS, "alarm_console.png"))
    print("alarm_console.png")


def make_connector():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 遥控器外壳
    d.rounded_rectangle([4, 2, 11, 14], radius=2, fill=(58, 64, 72, 255),
                        outline=(96, 106, 116, 255))
    # 小天线
    d.line([(8, 2), (8, 0)], fill=(130, 140, 150, 255))
    # 屏幕
    d.rectangle([6, 4, 9, 6], fill=(20, 40, 44, 255))
    # 三个按钮
    d.ellipse([6, 8, 7, 9], fill=(200, 70, 70, 255))
    d.ellipse([8, 8, 9, 9], fill=(70, 180, 100, 255))
    d.ellipse([6, 10, 9, 12], fill=(180, 150, 60, 255))
    img.save(os.path.join(ITEMS, "alarm_connector.png"))
    print("alarm_connector.png")


if __name__ == "__main__":
    make_light()
    make_console()
    make_connector()
