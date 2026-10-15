#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PNG 工具（离线、零依赖，只用标准库 zlib/struct）。

用途：把"深色纯色背景"的渲染图（如 Snipaste 截图）转成 Minecraft 物品贴图——
去背景 + 正确的 alpha 边缘 + 高质量降采样。

用法：
  python tools/pngtool.py inspect <src.png>
  python tools/pngtool.py matte <src.png> <dst.png> [size] [lo] [hi]
      size  输出边长（默认 64，正方形输出）
      lo/hi 背景判定阈值：距离 <= lo 全透明，>= hi 全不透明，中间线性过渡（默认 10 / 28）

说明：
  - 背景色自动取图像众数色（深色纯色底一般就是它）。
  - 合成公式假定源图 = 前景预乘色 叠在 bg 上，反解出真实颜色，避免暗色描边。
  - 降采样在"预乘 alpha"空间做盒式平均，再反预乘，边缘不会发黑。
"""
import struct
import sys
import zlib
from collections import Counter


def read_png(path):
    d = open(path, "rb").read()
    pos = 8
    idat = b""
    ihdr = None
    while pos < len(d):
        ln = struct.unpack(">I", d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", data)
        if typ == b"IDAT":
            idat += data
        pos += 12 + ln
    w, h, bd, ct, _, _, inter = ihdr
    if bd != 8 or inter != 0:
        raise ValueError("only 8-bit non-interlaced PNG supported, got bitdepth=%d interlace=%d" % (bd, inter))
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[ct]
    raw = zlib.decompress(idat)
    stride = w * ch
    out = bytearray()
    prev = bytearray(stride)
    i = 0
    for _y in range(h):
        ft = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        if ft == 1:
            for x in range(ch, stride):
                line[x] = (line[x] + line[x - ch]) & 255
        elif ft == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif ft == 3:
            for x in range(stride):
                a = line[x - ch] if x >= ch else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif ft == 4:
            for x in range(stride):
                a = line[x - ch] if x >= ch else 0
                b = prev[x]
                c = prev[x - ch] if x >= ch else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out += line
        prev = line
    return w, h, ch, bytes(out)


def write_png(path, w, h, rgba):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgba[y * w * 4:(y + 1) * w * 4]

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    out += chunk(b"IEND", b"")
    open(path, "wb").write(out)


def dominant_color(w, h, ch, px, step=1):
    cnt = Counter()
    for y in range(0, h, step):
        base = y * w * ch
        for x in range(0, w, step):
            i = base + x * ch
            cnt[(px[i], px[i + 1], px[i + 2])] += 1
    return cnt.most_common(1)[0][0], cnt


def to_matte(w, h, ch, px, bg, lo, hi):
    """返回 (r,g,b,a) 列表，尺寸不变。"""
    out = []
    for i in range(0, len(px), ch):
        r, g, b = px[i], px[i + 1], px[i + 2]
        dist = max(abs(r - bg[0]), abs(g - bg[1]), abs(b - bg[2]))
        if dist <= lo:
            out.append((0, 0, 0, 0))
            continue
        a = 1.0 if dist >= hi else (dist - lo) / float(hi - lo)
        # 反预乘：src = fg*a + bg*(1-a)  =>  fg = (src - bg*(1-a)) / a
        inv = 1.0 - a
        fr = (r - bg[0] * inv) / a
        fg_ = (g - bg[1] * inv) / a
        fb = (b - bg[2] * inv) / a
        out.append((
            max(0, min(255, int(round(fr)))),
            max(0, min(255, int(round(fg_)))),
            max(0, min(255, int(round(fb)))),
            max(0, min(255, int(round(a * 255.0)))),
        ))
    return out


def downscale_premul(src, w, h, n):
    """把 (r,g,b,a) 正方形图盒式降采样到 n x n，预乘空间平均。"""
    dst = []
    scale = w / float(n)
    for oy in range(n):
        y0 = int(oy * scale)
        y1 = max(y0 + 1, int((oy + 1) * scale))
        for ox in range(n):
            x0 = int(ox * scale)
            x1 = max(x0 + 1, int((ox + 1) * scale))
            sr = sg = sb = sa = 0.0
            cnt = 0
            for y in range(y0, min(y1, h)):
                base = y * w
                for x in range(x0, min(x1, w)):
                    r, g, b, a = src[base + x]
                    af = a / 255.0
                    sr += r * af
                    sg += g * af
                    sb += b * af
                    sa += af
                    cnt += 1
            if cnt == 0 or sa <= 0.0:
                dst.append((0, 0, 0, 0))
                continue
            dst.append((
                max(0, min(255, int(round(sr / sa)))),
                max(0, min(255, int(round(sg / sa)))),
                max(0, min(255, int(round(sb / sa)))),
                max(0, min(255, int(round(sa / cnt * 255.0)))),
            ))
    return dst


def crop_square(cells, w, h, margin=1, alpha_min=8):
    """裁到不透明内容的正方形包围盒（含 margin），让贴图在物品格里占满。"""
    x0, y0, x1, y1 = w, h, -1, -1
    for y in range(h):
        base = y * w
        for x in range(w):
            if cells[base + x][3] > alpha_min:
                if x < x0:
                    x0 = x
                if x > x1:
                    x1 = x
                if y < y0:
                    y0 = y
                if y > y1:
                    y1 = y
    if x1 < x0 or y1 < y0:
        return cells, w, h, (x0, y0, x1, y1)
    x0 = max(0, x0 - margin)
    y0 = max(0, y0 - margin)
    x1 = min(w - 1, x1 + margin)
    y1 = min(h - 1, y1 + margin)
    cw = x1 - x0 + 1
    ch_ = y1 - y0 + 1
    side = max(cw, ch_)
    # 以内容中心为基准扩成正方形，尽量不越界
    cx = (x0 + x1) // 2
    cy = (y0 + y1) // 2
    sx = max(0, min(w - side, cx - side // 2))
    sy = max(0, min(h - side, cy - side // 2))
    side = min(side, w - sx, h - sy)
    out = []
    for y in range(sy, sy + side):
        base = y * w
        out.extend(cells[base + sx:base + sx + side])
    return out, side, side, (x0, y0, x1, y1)


def flatten(cells, n, bg):
    """把透明图铺在指定底色上，用于人工目检边缘是否发黑。"""
    out = []
    for (r, g, b, a) in cells:
        af = a / 255.0
        out.append((
            int(round(r * af + bg[0] * (1 - af))),
            int(round(g * af + bg[1] * (1 - af))),
            int(round(b * af + bg[2] * (1 - af))),
            255,
        ))
    return out


def cells_to_rgba(cells):
    buf = bytearray()
    for (r, g, b, a) in cells:
        buf += bytes((r, g, b, a))
    return bytes(buf)


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 1
    cmd = argv[1]
    src = argv[2]
    w, h, ch, px = read_png(src)

    if cmd == "inspect":
        bg, cnt = dominant_color(w, h, ch, px)
        print("size      : %dx%d  channels=%d" % (w, h, ch))
        print("dominant  : rgb%s  (%.1f%% of pixels)" % (bg, 100.0 * cnt[bg] / (w * h)))
        for c, n in cnt.most_common(5):
            print("            rgb%s x%d" % (c, n))
        return 0

    if cmd == "matte":
        dst = argv[3] if len(argv) > 3 else "out.png"
        size = int(argv[4]) if len(argv) > 4 else 64
        lo = int(argv[5]) if len(argv) > 5 else 10
        hi = int(argv[6]) if len(argv) > 6 else 28
        bg, _ = dominant_color(w, h, ch, px)
        cells = to_matte(w, h, ch, px, bg, lo, hi)
        if "--nocrop" not in argv:
            cells, w, h, bbox = crop_square(cells, w, h)
            print("cropped to square  bbox=%s" % (bbox,))
        cells = downscale_premul(cells, w, h, size)
        write_png(dst, size, size, cells_to_rgba(cells))
        opaque = sum(1 for c in cells if c[3] > 200)
        print("bg=rgb%s  -> %s (%dx%d)  opaque=%d/%d (%.1f%%)"
              % (bg, dst, size, size, opaque, size * size, 100.0 * opaque / (size * size)))
        check = dst.replace(".png", "_onwhite.png")
        write_png(check, size, size, cells_to_rgba(flatten(cells, size, (255, 255, 255))))
        print("preview over white -> " + check)
        return 0

    print("unknown command: " + cmd)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
