# -*- coding: utf-8 -*-
"""离线预览灯圈控制台 GUI 的版面（不需要启动 Minecraft）。
与 mock_laser_gui 同一套做法：GuiRingScreen 的常量照抄一遍，肉眼看出/重叠/出框。
"""
import io, os, re, sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "src", "main", "java", "com", "jiezhimod", "client", "gui", "GuiRingConsole.java")

src = io.open(SRC, encoding="utf-8").read()
C = {}
for m in re.finditer(r"private static final int (\w+)\s*=\s*([^;]+);", src):
    try: C[m.group(1)] = int(eval(m.group(2).strip(), {}, dict(C)))
    except Exception: pass
W, H, M, RX = C["W"], C["H"], C["M"], C["RIGHT_X"]
FONT = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 9)
FS = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 8)
SCALE = 3
img = Image.new("RGB", (W*SCALE, H*SCALE), (58,74,52))
d = ImageDraw.Draw(img)
def rect(x,y,w,h,fill=None,outline=None):
    d.rectangle([x*SCALE,y*SCALE,(x+w)*SCALE-1,(y+h)*SCALE-1],fill=fill,outline=outline)
def text(s,x,y,c=(200,200,200)):
    d.text((x*SCALE,y*SCALE), s, font=FS, fill=c)
def btn(x,y,w,h,label):
    rect(x,y,w,h,fill=(150,150,150),outline=(60,60,60))
    tw=d.textlength(label,font=FS); text(label, x+(w-tw/SCALE)/2, y+(h-8)/2-1, (30,30,30))
d.rectangle([0,0,W*SCALE-1,H*SCALE-1], fill=(10,10,15))
t="灯圈控制台"; tw=d.textlength(t,font=FONT); text(t,(W*SCALE-tw)/2/SCALE,4,(255,255,255))
# 左列
for i,lab in enumerate(["■ 切换颜色至","□ 开启灯圈","□ 关闭灯圈","■ 闪烁"]):
    btn(M, 26+i*18, 112, 15, lab)
text("颜色 RGB", M, 99)
for i,(lab,x) in enumerate((("R",M),("G",40),("B",74))):
    text(lab,x,99,(255,107,107) if i==0 else ((107,255,138) if i==1 else (107,182,255)))
    rect(x,108,28,14,fill=(21,24,31),outline=(60,70,85))
    text("255" if i==0 else "58", x+3, 110, (220,220,220))
rect(106,108,20,14,fill=(255,58,58))
for i,c in enumerate([(255,58,58),(255,154,43),(255,225,77),(74,222,107),(62,155,255),(164,92,255),(242,246,250)]):
    btn(M+i*16,126,14,14,"")
    rect(M+i*16+2,128,10,10,fill=c)
text("预设色", M, 143)
btn(M,154,56,16,"应用"); btn(68,154,56,16,"关闭")
text("已绑定灯圈 12 64 -3", M, 176, (82,224,138))
text("上次触发：#00FFFF（渐变）  [4 格]", M, 188, (120,130,140))
text("点「应用」保存配置；多台控制台用红石中继器延迟串起来", M, 200, (119,119,119))
text("就能做出「淡入到红-停两秒-淡入到蓝」的流程", M, 209, (119,119,119))
# 右列
rows=[("切换颜色",26),("开启灯圈",55),("关闭灯圈",85)]
for i,(lab,y) in enumerate(rows):
    text(lab, RX, 16+i*30)
    btn(RX, y, 88, 15, "直接切换" if i==0 else ("普通（立即）"))
    rect(224, y+1, 40, 14, fill=(21,24,31), outline=(60,70,85)); text("0.50", 228, y+3)
    text("秒", 268, y+4)
text("闪烁参数", RX, 106)
for x,lab in ((RX,"0.50"),(210,"5.00")):
    rect(x,115,34,14,fill=(21,24,31),outline=(60,70,85)); text(lab,x+3,117)
text("秒/周期",170,119); text("秒总长",248,119)
for x,lab in ((RX,"0.00"),(210,"0.00")):
    rect(x,135,34,14,fill=(21,24,31),outline=(60,70,85)); text(lab,x+3,137)
text("秒淡入",170,139); text("秒淡出",248,139)
btn(RX,155,112,15,"普通闪烁（重复关开）")
out = sys.argv[1] if len(sys.argv)>1 else os.path.join(ROOT,"screenshots","gui-ring-mock.png")
img.save(out); print("mock written:", out, "panel=%dx%d" % (W,H))
