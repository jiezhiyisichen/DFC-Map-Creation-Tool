# -*- coding: utf-8 -*-
"""离线预览自定义显示屏 GUI 的版面（不需要启动 Minecraft）。

用法：
    python tools/gui_mock/mock_custom_gui.py [输出png]

为什么要有这个工具：GuiContainer 的真实像素只能进游戏才看得到，
但"有没有出框 / 有没有重叠 / 有没有超屏"这类错误**不需要**真实渲染就能发现。
这里把 ContainerCustomScreen 里的版面常量照抄一遍（有 selfcheck 兜底比对），
用 PIL 画成图，肉眼一看就知道排版对不对。

注意：字体是近似的（微软雅黑 8px 代替 Minecraft 的 unicode 字体），
      宽度会略有出入，只用来判断"结构"是否正确，不要拿它抠像素。
"""
import io
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CONST_SRC = os.path.join(ROOT, "src", "main", "java", "com", "jiezhimod", "block",
                         "ContainerCustomScreen.java")

# ---- 从源码里读常量，保证和 Java 那份是同一组数字（不抄第二遍） ----
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

FONT = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 9)
FONT_S = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 8)

SCALE = 3
PANEL_BG = (10, 10, 15)
LINE = (40, 50, 64)


def new_canvas():
    img = Image.new("RGB", (W * SCALE, H * SCALE), (58, 74, 52))  # 假装背后是世界
    return img, ImageDraw.Draw(img)


def rect(d, x, y, w, h, fill=None, outline=None):
    d.rectangle([x * SCALE, y * SCALE, (x + w) * SCALE - 1, (y + h) * SCALE - 1],
                fill=fill, outline=outline)


def text(d, s, x, y, color, small=False):
    d.text((x * SCALE, y * SCALE), s, font=FONT_S if small else FONT, fill=color)


def button(d, x, y, w, h, label):
    rect(d, x, y, w, h, fill=(150, 150, 150), outline=(60, 60, 60))
    tw = d.textlength(label, font=FONT_S)
    text(d, label, x + (w - tw / SCALE) / 2, y + (h - 8) / 2 - 1, (35, 35, 35), small=True)


def draw():
    img, d = new_canvas()

    # 面板
    d.rectangle([0, 0, W * SCALE - 1, H * SCALE - 1], fill=PANEL_BG)

    # 标题
    title = "DFC 自定义显示屏"
    tw = d.textlength(title, font=FONT)
    text(d, title, (W * SCALE - tw) / 2 / SCALE, C["TITLE_Y"], (255, 255, 255))

    # 左区：电源
    button(d, M, C["POWER_Y"], C["POWER_W"], 16, "关机")
    text(d, "当前：已开机", M, C["STATE_Y"], (82, 224, 138))

    # 左区：遥控器槽
    sx, sy = C["REMOTE_SLOT_X"], C["REMOTE_SLOT_Y"]
    rect(d, sx, sy, 18, 18, fill=(21, 24, 31), outline=(60, 90, 120))
    text(d, "遥控器", C["REMOTE_LABEL_X"], C["REMOTE_LABEL_Y"], (154, 167, 180))
    text(d, "空", C["REMOTE_LABEL_X"] + 33, C["REMOTE_LABEL_Y"], (106, 116, 128))

    # 左区：添加
    button(d, C["ADD_BTN_X"], C["ADD_BTN_Y"], C["ADD_BTN_W"], 16, "添加")
    text(d, "把遥控器放进槽位", C["ADD_HINT_X"], C["ADD_HINT_Y"], (106, 116, 128), small=True)

    # 右区：8 行列表
    lx = C["LIST_X"]
    delx = W - M - C["DEL_W"]
    for i in range(8):
        y = C["LIST_TOP"] + i * C["ROW_H"]
        if i % 2 == 0:
            rect(d, lx - 4, y - 1, (W - M) - (lx - 4), C["ROW_H"], fill=(30, 30, 34))
        text(d, "%d." % (i + 1), lx, y + 2, (102, 102, 102), small=True)
        text(d, "（空）", lx + 12, y + 2, (68, 68, 68), small=True)
        button(d, delx, y, C["DEL_W"], C["DEL_H"], "删除")

    # 底部提示（2 行）
    text(d, "放入 DFC 遥控器，点「添加」接入它记录的全部设备（最多 8 条）",
         M, C["HINT_Y"], (119, 119, 119), small=True)

    # 清空 / 关闭
    button(d, M, C["BTN_ROW_Y"], C["CLEAR_W"], 16, "清空")
    button(d, C["CLOSE_X"], C["BTN_ROW_Y"], C["CLOSE_W"], 16, "关闭")

    # 玩家背包 + 快捷栏
    ix, iy = C["INV_X"], C["INV_Y"]
    rect(d, ix - 3, iy - 3, 9 * 18 + 5, (C["HOTBAR_Y"] - iy) + 18 + 5, fill=(16, 16, 22))
    for r in range(3):
        for c in range(9):
            rect(d, ix + c * 18, iy + r * 18, 18, 18, fill=(21, 24, 31), outline=(42, 51, 64))
    for c in range(9):
        rect(d, ix + c * 18, C["HOTBAR_Y"], 18, 18, fill=(21, 24, 31), outline=(42, 51, 64))

    return img


def selfcheck():
    """把自测里那几条硬断言在这里复算一遍，图不对时先报错。"""
    problems = []
    if H > 240:
        problems.append("PANEL_H=%d > 240（会超出屏幕，标题出屏）" % H)
    if W > 312:
        problems.append("PANEL_W=%d > 312（6 倍缩放下逻辑宽约 319）" % W)
    list_bottom = C["LIST_TOP"] + 8 * C["ROW_H"]
    if list_bottom > C["HINT_Y"]:
        problems.append("列表底 %d 压到提示 %d" % (list_bottom, C["HINT_Y"]))
    if C["HINT_Y"] + 2 * 9 > C["BTN_ROW_Y"]:
        problems.append("提示第 2 行会压到按钮行")
    if C["BTN_ROW_Y"] + 16 > C["INV_Y"]:
        problems.append("底部按钮压到背包")
    if C["INV_Y"] + 3 * 18 > C["HOTBAR_Y"]:
        problems.append("背包压到快捷栏")
    if C["HOTBAR_Y"] + 18 > H:
        problems.append("快捷栏出面板底边")
    if M + C["CLEAR_W"] > C["CLOSE_X"]:
        problems.append("清空与关闭按钮重叠")
    if C["REMOTE_SLOT_Y"] + 18 > C["ADD_BTN_Y"]:
        problems.append("遥控器槽压到添加按钮")
    if C["ADD_BTN_Y"] + 16 > C["ADD_HINT_Y"]:
        problems.append("添加按钮压到它下面的提示")
    return problems


if __name__ == "__main__":
    bad = selfcheck()
    if bad:
        print("SELFCHECK FAILED:")
        for b in bad:
            print("  -", b)
        sys.exit(1)
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "screenshots",
                                                            "gui-custom-mock.png")
    draw().save(out)
    print("mock written:", out, "  panel = %dx%d" % (W, H))
