# -*- coding: utf-8 -*-
"""生成高级红石中继器的 8 个模型 + 32 条 blockstate。

【为什么用脚本生成而不是手写】
两个指示灯 = 输入/输出各 2 态，再乘锁定 2 态 = 8 个模型；
变体还要再乘 4 个朝向 = 32 条。手写两遍（off/on）就已经开始各写各的了，
再加两个维度必然写错一条 —— 而写错的那一条在游戏里表现为"某个朝向的
中继器少一个火把"，极难定位。

【几何来源】直接读现有的 advanced_repeater_off / _on / _off_locked 三个模型，
不重新编数字：底座、火把、锁定黑条的形状都沿用已经在游戏里看过的样子。

用法：python tools/gen_repeater_models.py
"""
import io
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod", "models", "block")
STATES = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod", "blockstates")

# ★ faces.<面>.texture **只能写 `#变量名`**，不能写资源路径！
#   （2026-10-30 踩过一次：写成 "minecraft:blocks/redstone_torch_on" 之后
#     ModelBlock.resolveTextureName 会给它前面补一个 '#'，查不到就整个模型加载失败，
#     游戏里表现为"方块变成紫黑格"的大乱码。）
TEX_LIT = "#lit"
TEX_UNLIT = "#unlit"
TEX_LOCK = "#lock"

# 朝向 -> y 旋转（与原来的 blockstate 一致）
FACING = [("south", 0), ("west", 90), ("north", 180), ("east", 270)]


def load(name):
    with io.open(os.path.join(MODELS, name + ".json"), encoding="utf-8") as fh:
        return json.load(fh)


def with_texture(element, tex):
    """把元素所有面的贴图换成 tex（uv / cullface / rotation 全保留）"""
    out = json.loads(json.dumps(element))
    for face in out["faces"].values():
        face["texture"] = tex
    return out


def element_at(model, index):
    return json.loads(json.dumps(model["elements"][index]))


def build(off, on, locked_model, locked, powered, inset):
    """拼一个模型：底座 + 输入位（火把或黑条） + 输出位火把"""
    slab = element_at(off, 0)

    if locked:
        # 锁定时输入端那根火把换成横向黑条（原版就是这个观感）
        input_part = [element_at(locked_model, 1)]
    else:
        # _on 里的 1..3 是**输入端**（局部 +Z 那根），4..6 是输出端
        tex = TEX_LIT if inset else TEX_UNLIT
        input_part = [with_texture(element_at(on, i), tex) for i in (1, 2, 3)]

    tex_out = TEX_LIT if powered else TEX_UNLIT
    output_part = [with_texture(element_at(on, i), tex_out) for i in (4, 5, 6)]

    return {
        "ambientocclusion": False,
        "textures": {
            "particle": "minecraft:blocks/repeater_off",
            "slab": "minecraft:blocks/stone_slab_top",
            "top": "minecraft:blocks/repeater_off",
            "lock": "minecraft:blocks/bedrock",
            "lit": "minecraft:blocks/redstone_torch_on",
            "unlit": "minecraft:blocks/redstone_torch_off",
        },
        "elements": [slab] + input_part + output_part,
    }


def model_name(locked, powered, inset):
    """名字沿用现有的 off/on 两个（向后兼容），另外两个新增"""
    if not locked:
        if not powered and not inset:
            return "advanced_repeater_off"
        if not powered and inset:
            return "advanced_repeater_in"
        if powered and not inset:
            return "advanced_repeater_on"
        return "advanced_repeater_both"
    if not powered and not inset:
        return "advanced_repeater_off_locked"
    if not powered and inset:
        return "advanced_repeater_in_locked"
    if powered and not inset:
        return "advanced_repeater_on_locked"
    return "advanced_repeater_both_locked"


def main():
    off = load("advanced_repeater_off")
    on = load("advanced_repeater_on")
    locked_model = load("advanced_repeater_off_locked")

    variants = {}
    written = []
    for locked in (False, True):
        for powered in (False, True):
            for inset in (False, True):
                name = model_name(locked, powered, inset)
                body = build(off, on, locked_model, locked, powered, inset)
                path = os.path.join(MODELS, name + ".json")
                with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
                    json.dump(body, fh, indent=4, ensure_ascii=False)
                    fh.write("\n")
                written.append((name, len(body["elements"])))

                # ★★ key 里属性的书写顺序**必须**是属性名的字母序：
                #   facing < input < locked < powered
                #   BlockStateContainer 构造时按属性名排序生成 propertiesList，
                #   ModelResourceLocation 的 variant 字符串由它拼出来；blockstates 的 key
                #   是原样字符串直接查表 ⇒ 顺序不一致就 MissingVariantException（紫黑棋盘格）。
                key = "input=%s,locked=%s,powered=%s" % (
                    "true" if inset else "false",
                    "true" if locked else "false",
                    "true" if powered else "false")
                variants[key] = name

    # blockstate：4 朝向 x 8 组合
    out = {"variants": {}}
    for facing, y in FACING:
        for key, model in variants.items():
            v = {"model": "jiezhimod:" + model}
            if y:
                v["y"] = y
            out["variants"]["facing=%s,%s" % (facing, key)] = v

    path = os.path.join(STATES, "advanced_repeater.json")
    with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, indent=4, ensure_ascii=False)
        fh.write("\n")

    for name, n in written:
        print("  model %-34s elements=%d" % (name, n))
    print("  blockstate variants=%d" % len(out["variants"]))


if __name__ == "__main__":
    main()
