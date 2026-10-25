# -*- coding: utf-8 -*-
"""离线预览**激光状态显示器面板**的版面（不需要启动 Minecraft）。

用法：
    python tools/gui_mock/mock_laser_gui.py [输出png]

和 mock_custom_gui.py 同一套做法：把 ContainerLaserScreen 里的版面常量读出来
（不抄第二遍），按 GuiLaserScreen.drawList / drawConnectorRow 的算法画一遍。

为什么这块屏单独一个 mock：v4.2 它是套用自定义屏面板的，于是列表按 8 行画；
重写之后固定 4 行、行里两行字 + 数据条，**结构变了**，得能一眼看出有没有出框/重叠。

字体是近似的（微软雅黑 9px 代替 Minecraft 的 unicode 字体），只判断结构，不抠像素。
"""
import io
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CONST_SRC = os.path.join(ROOT, "src", "main", "java", "com", "jiezhimod", "block",
                         "ContainerLaserScreen.java")


def load_consts():
    src = io.open(CONST_SRC, encoding="utf-8").read()
    vals = {}
    for m in re.finditer(r"public static final int (\w+)\s*=\s*([^;]+);", src):
        name, expr = m.group(1), m.group(2).strip()
        try:
            vals[name] = int(eval(expr, {}, dict(vals)))  # noqa: S307 — 只算加减乘除
        except Exception:
            pass
    return vals


C = load_consts()
W, H = C["PANEL_W"], C["PANEL_H"]
M = C["MARGIN"]
SLOTS = 4  # = LaserLayout.SLOTS

FONT = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 9)
FONT_S = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 8)

SCALE = 3
PANEL_BG = (10, 10, 15)
C_SL = (255, 193, 61)
C_PL = (255, 90, 74)
C_OL = (127, 210, 255)
C_INJ = (233, 239, 245)
C_OTHER = (207, 218, 228)


def new_canvas():
    img = Image.new("RGB", (W * SCALE, H * SCALE), (58, 74, 52))
    return img, ImageDraw.Draw(img)


def rect(d, x, y, w, h, fill=None, outline=None):
    d.rectangle([x * SCALE, y * SCALE, (x + w) * SCALE - 1, (y + h) * SCALE - 1],
                fill=fill, outline=outline)


def text(d, s, x, y, color, small=False):
    d.text((x * SCALE, y * SCALE), s, font=FONT_S if small else FONT, fill=color)


def trimmed(d, s, maxw, small=False):
    """按像素裁（对应 FontRenderer.trimStringToWidth）"""
    f = FONT_S if small else FONT
    if d.textlength(s, font=f) / SCALE <= maxw:
        return s
    while s and d.textlength(s, font=f) / SCALE > maxw:
        s = s[:-1]
    return s


def button(d, x, y, w, h, label):
    rect(d, x, y, w, h, fill=(150, 150, 150), outline=(60, 60, 60))
    tw = d.textlength(label, font=FONT_S)
    text(d, label, x + (w - tw / SCALE) / 2, y + (h - 8) / 2 - 1, (35, 35, 35), small=True)


# 假数据：两台真激光 + 两个空槽（正好覆盖三种行状态）
ROWS = [
    ("SL1", "稳定器", C_SL, True, "透镜耐久 12.40%", 0.124, (255, 193, 61), True),
    ("PL1", "发射器", C_PL, True, "冷凝胶 21.00%", 0.21, (255, 138, 43), False),
]
EMPTY = ("（空）", "用激光链接器接入一台激光")


def draw():
    img, d = new_canvas()
    d.rectangle([0, 0, W * SCALE - 1, H * SCALE - 1], fill=PANEL_BG)

    # 标题
    title = "DFC 激光状态显示器"
    tw = d.textlength(title, font=FONT)
    text(d, title, (W * SCALE - tw) / 2 / SCALE, C["TITLE_Y"], (255, 255, 255))

    # ---- 左区 ----
    button(d, C["POWER_X"], C["POWER_Y"], C["POWER_W"], 16, "关机")
    text(d, "当前：已开机", M, C["STATE_Y"], (82, 224, 138))

    sx, sy = C["LINK_SLOT_X"], C["LINK_SLOT_Y"]
    rect(d, sx, sy, 18, 18, fill=(21, 24, 31), outline=(60, 90, 120))
    text(d, "激光链接器", C["LINK_LABEL_X"], C["LINK_LABEL_Y"], (154, 167, 180))
    left_right = C["LIST_X"] - 4
    text(d, trimmed(d, "已记录 2 / 4 台", left_right - C["LINK_LABEL_X"]),
         C["LINK_LABEL_X"], C["LINK_COUNT_Y"], (127, 227, 160))

    button(d, C["ADD_BTN_X"], C["ADD_BTN_Y"], C["ADD_BTN_W"], 16, "添加")
    text(d, trimmed(d, "把激光链接器放进槽位", left_right - C["ADD_BTN_X"]),
         C["ADD_BTN_X"], C["ADD_HINT_Y"], (106, 116, 128), small=True)

    text(d, "已接入 2 台 / 最多 4 台", M, C["COUNT_Y"], (154, 167, 180))

    button(d, C["CLEAR_X"], C["CLEAR_Y"], C["CLEAR_W"], 16, "清空")
    button(d, C["CLOSE_X"], C["CLEAR_Y"], C["CLOSE_W"], 16, "关闭")

    # ---- 右区：固定 4 行 ----
    lx = C["LIST_X"]
    delx = W - M - C["DEL_W"]
    right_x = delx - 4
    row_w = (W - M) - lx + 4
    for i in range(SLOTS):
        y = C["LIST_TOP"] + i * C["ROW_H"]
        if i % 2 == 0:
            rect(d, lx - 4, y - 1, row_w, C["ROW_H"], fill=(30, 30, 34))
        if i >= len(ROWS):
            text(d, EMPTY[0], lx, y + 2, (68, 74, 82), small=True)
            text(d, EMPTY[1], lx, y + 13, (68, 74, 82), small=True)
            button(d, delx, y + C["DEL_DY"], C["DEL_W"], C["DEL_H"], "删除")
            continue

        label, kind, color, on, value, ratio, warn_color, has_warn = ROWS[i]
        rect(d, lx, y + 3, 5, 5, fill=color if on else (42, 48, 56))
        head = label + " " + kind
        text(d, trimmed(d, head, right_x - lx - 10), lx + 10, y + 2, color)

        bar_x = right_x - 40
        rect(d, bar_x, y + 17, 40, 3, fill=(30, 36, 44))
        fill_w = max(0, min(40, int(round(40 * ratio))))
        if fill_w:
            rect(d, bar_x, y + 17, fill_w, 3, fill=warn_color)
        text(d, trimmed(d, value, bar_x - 4 - (lx + 10)), lx + 10, y + 13, warn_color)
        if has_warn:
            text(d, "!", right_x - 3, y + 2, (255, 193, 61))
        button(d, delx, y + C["DEL_DY"], C["DEL_W"], C["DEL_H"], "删除")

    # ---- 底部提示 ----
    text(d, trimmed(d, "蹲下右键激光部件记录 → 把链接器放进槽位点「添加」；最多 4 台，一行一台",
                    W - M * 2),
         M, C["HINT_Y"], (119, 119, 119), small=True)

    # ---- 玩家背包 + 快捷栏 ----
    ix, iy = C["INV_X"], C["INV_Y"]
    rect(d, ix - 3, iy - 3, 9 * 18 + 5, (C["HOTBAR_Y"] - iy) + 18 + 5, fill=(16, 16, 22))
    for r in range(3):
        for c in range(9):
            rect(d, ix + c * 18, iy + r * 18, 18, 18, fill=(21, 24, 31), outline=(42, 51, 64))
    for c in range(9):
        rect(d, ix + c * 18, C["HOTBAR_Y"], 18, 18, fill=(21, 24, 31), outline=(42, 51, 64))

    return img


def selfcheck():
    """和 SelfTest.testLaserScreenGuiLayout 同一批判据，图不对时先报错。"""
    p = []
    if H > 240:
        p.append("PANEL_H=%d > 240" % H)
    if W > 312:
        p.append("PANEL_W=%d > 312" % W)
    if SLOTS != 4:
        p.append("行数不是 4")
    if C["LIST_TOP"] + SLOTS * C["ROW_H"] > C["HINT_Y"]:
        p.append("4 行列表压到提示")
    if C["HINT_Y"] + 2 * 9 > C["INV_Y"]:
        p.append("提示压到背包")
    if C["CLEAR_Y"] + 16 > C["HINT_Y"]:
        p.append("清空/关闭按钮压到提示")
    if C["CLEAR_X"] + C["CLEAR_W"] > C["CLOSE_X"]:
        p.append("清空与关闭按钮重叠")
    if M + C["POWER_W"] > C["LIST_X"] - 4:
        p.append("关机按钮压到右区列表")
    if C["LINK_SLOT_Y"] + 18 > C["ADD_BTN_Y"]:
        p.append("链接器槽压到添加按钮")
    if C["ADD_BTN_Y"] + 16 > C["ADD_HINT_Y"]:
        p.append("添加按钮压到它下面的提示")
    if C["ADD_HINT_Y"] + 8 > C["COUNT_Y"]:
        p.append("添加提示压到「已接入 n/4」")
    if C["COUNT_Y"] + 8 > C["CLEAR_Y"]:
        p.append("「已接入 n/4」压到按钮行")
    if C["INV_Y"] + 3 * 18 > C["HOTBAR_Y"]:
        p.append("背包压到快捷栏")
    if C["HOTBAR_Y"] + 18 > H:
        p.append("快捷栏出面板底边")
    if C["DEL_DY"] + C["DEL_H"] > C["ROW_H"]:
        p.append("行内删除按钮越到下一行")
    return p


if __name__ == "__main__":
    bad = selfcheck()
    if bad:
        print("SELFCHECK FAILED:")
        for b in bad:
            print("  -", b)
        sys.exit(1)
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "screenshots", "gui-laser-mock.png")
    draw().save(out)
    print("mock written:", out, "  panel = %dx%d  rows=%d" % (W, H, SLOTS))
