# -*- coding: utf-8 -*-
"""
生成灯圈的「灭」态贴图 light_ring_off.png。

【为什么需要一张单独的贴图】
灯圈的亮/灭如果只靠 tint 把颜色压暗，观感上几乎没有变化 ——
tint 是**烘焙时**采样进顶点色的，区块不重建就不刷新；
而且 1.12.2 的方块光只有亮度没有颜色，白天户外"光没了"这件事本身很不明显。
原版红石灯解决同一个问题的办法就是**亮/灭两张贴图**，这里照抄：
灭态用一张暗色面，由方块状态 POWERED 直接切模型 ⇒ 服务端一翻状态
客户端立刻换贴图，绝对看得见，不依赖 tint 的采样时机。

颜色仍然由亮态那张（tintindex=0）表达，灭态不带 tintindex。
"""
import io
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, os.pardir, "src", "main", "resources", "assets",
                   "jiezhimod", "textures", "blocks", "light_ring_off.png")

SIZE = 16
BASE = 0x3C          # 暗灰中性色（关掉的灯该有的样子）
JITTER = 4           # ±4 噪点，避免大面积纯色显得像没画完


def main():
    random.seed(20261030)
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 255))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            v = BASE + random.randint(-JITTER, JITTER)
            v = max(0, min(255, v))
            px[x, y] = (v, v, v, 255)
    img.save(OUT)
    print("wrote %s  %dx%d  base=#%02X%02X%02X +-%d" %
          (os.path.relpath(OUT, os.path.join(HERE, os.pardir)), SIZE, SIZE, BASE, BASE, BASE, JITTER))


if __name__ == "__main__":
    main()
