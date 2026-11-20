#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
JiezhiMod 离线自测脚本：编译 src + tools/selftest/SelfTest.java 并运行，
最后再扫一遍最终 jar，确认关键字符串没有明文残留。

用法：  python tools/selftest/run_selftest.py
依赖：  JDK 21 (javac/java 全路径见下方常量)
"""
import glob
import os
import io
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _jdk_bin():
    """探测一个可用的 JDK（≥9，因为编译用到 --release 8），按版本取最高。"""
    ext = ".exe" if os.name == "nt" else ""
    cands = []
    jh = os.environ.get("JAVA_HOME")
    if jh:
        cands.append(os.path.join(jh, "bin"))
    found = shutil.which("javac" + ext) or shutil.which("javac")
    if found:
        cands.append(os.path.dirname(found))
    for pat in (r"C:\Program Files\Microsoft\jdk-*\bin",
                r"C:\Program Files\Java\jdk-*\bin",
                r"C:\Program Files\Eclipse Adoptium\jdk*\bin",
                r"C:\Program Files\Amazon Corretto\jdk*\bin",
                "/usr/lib/jvm/*/bin",
                "/opt/java/*/bin"):
        cands.extend(glob.glob(pat))

    def major(b):
        try:
            with open(os.path.join(os.path.dirname(b), "release"), encoding="utf-8",
                      errors="replace") as fh:
                m = re.search(r'JAVA_VERSION="(\d+)', fh.read())
                return int(m.group(1)) if m else 0
        except OSError:
            return 0

    best, best_ver = None, 0
    for b in cands:
        if not os.path.isfile(os.path.join(b, "javac" + ext)):
            continue
        v = major(b)
        if v >= 9 and v > best_ver:
            best, best_ver = b, v
    if not best:
        raise SystemExit("找不到可用的 JDK（需要 9+）。请设置 JAVA_HOME。")
    return best


JDK = _jdk_bin()
JAVAC = os.path.join(JDK, "javac.exe" if os.name == "nt" else "javac")
JAVA = os.path.join(JDK, "java.exe" if os.name == "nt" else "java")
GRADLE_HOME = os.path.join(os.path.expanduser("~"), ".gradle")


def final_jar():
    """挑出**最新的**产物 jar。

    ★ 这里踩过一次坑：原来用 sorted(...)[0]，也就是按字母序取第一个 ——
    build/libs 里同时躺着 3.5/3.6/3.7/3.8 时，它永远挑到 **3.5**，
    于是"资源是否打包 / 是否有明文密钥 / TileEntity 是否注册"这些 jar 级检查
    全在验一个旧包，新加的纹理缺了也照样 ALL GREEN。
    改成按修改时间取最新，才真正在测刚构建出来的那个。
    """
    cands = [p for p in glob.glob(os.path.join(ROOT, "build", "libs", "*.jar"))
             if "sources" not in os.path.basename(p) and "javadoc" not in os.path.basename(p)]
    if not cands:
        raise SystemExit("找不到 build/libs 下的最终 jar，请先构建")
    return max(cands, key=os.path.getmtime)


FINAL_JAR = final_jar()
OUT = os.path.join(ROOT, "tools", "selftest", "out")


def forge_jar():
    base = os.path.join(GRADLE_HOME, "caches", "forge_gradle", "minecraft_user_repo",
                        "net", "minecraftforge", "forge")
    for dirpath, _dirs, files in os.walk(base):
        for f in files:
            if f.endswith("-recomp.jar"):
                return os.path.join(dirpath, f)
    raise RuntimeError("recomp.jar not found under %s" % base)


def extra_jars():
    """gradle 依赖缓存里所有 jar（贪心，避免缺类）"""
    jars = []
    base = os.path.join(GRADLE_HOME, "caches", "modules-2", "files-2.1")
    for dirpath, _dirs, files in os.walk(base):
        for f in files:
            if f.endswith(".jar") and "sources" not in f and "javadoc" not in f:
                jars.append(os.path.join(dirpath, f))
    return jars


def sources():
    out = []
    src_root = os.path.join(ROOT, "src", "main", "java")
    for dirpath, _dirs, files in os.walk(src_root):
        for f in files:
            if f.endswith(".java"):
                out.append(os.path.join(dirpath, f))
    out.append(os.path.join(ROOT, "tools", "selftest", "SelfTest.java"))
    return out


def run(cmd):
    """JVM 子进程在 Windows 上可能以 GBK 输出（javac 的中文报错尤其明显），
    以 utf-8 解会变成乱码。这里两种编码都试，取没有替换字符的那种。"""
    p = subprocess.run(cmd, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    data = p.stdout
    for enc in ("utf-8", "gbk"):
        txt = data.decode(enc, errors="replace")
        if chr(0xFFFD) not in txt:
            return p.returncode, txt
    return p.returncode, data.decode("utf-8", errors="replace")


def main():
    cp_list = [forge_jar()] + extra_jars()
    cp = ";".join(cp_list)
    print("[1/3] compiling %d sources ..." % len(sources()))

    argfile = os.path.join(ROOT, "tools", "selftest", "sources.txt")
    with open(argfile, "w", encoding="utf-8") as fh:
        for s in sources():
            fh.write(s + "\n")

    rc, out = run([JAVAC, "--release", "8", "-encoding", "UTF-8", "-nowarn",
                   "-cp", cp, "-d", OUT, "@" + argfile])
    if rc != 0:
        print(out)
        print("COMPILE FAILED")
        return 1
    print("      compile OK")

    print("[2/3] running self-test ...")
    rc, out = run([JAVA, "-Dfile.encoding=UTF-8", "-cp", OUT + ";" + cp,
                   "com.jiezhimod.selftest.SelfTest"])
    print(out)
    with open(os.path.join(ROOT, "tools", "selftest", "last_result.txt"), "w",
              encoding="utf-8") as fh:
        fh.write(out)
    if rc != 0:
        print("SELF-TEST FAILED (exit %d)" % rc)
        return rc

    print("[3/4] validating mcmod.info (must be strict JSON) ...")
    import json
    try:
        with open(os.path.join(ROOT, "src", "main", "resources", "mcmod.info"),
                  encoding="utf-8") as fh:
            meta = json.load(fh)
        assert isinstance(meta, list) and meta, "mcmod.info must be a non-empty array"
        assert meta[0]["modid"] == "jiezhimod", "wrong modid"
        assert meta[0]["version"] and meta[0]["mcversion"] == "1.12.2", "version/mcversion bad"
        desc = meta[0]["description"]
        assert "speed_coil" in desc, "description does not document the speed coil"
        assert "gravity_coil" in desc, "description does not document the gravity coil"
        assert "healing_coil" in desc, "description does not document the healing coil"
        assert "countdown_add" in desc, "description lost the command manual"
        assert "/notice" in desc, "description does not document /notice"
        # DFC 显示器是最大的一块硬件，说明书里漏掉的话玩家根本不知道有第二块屏
        assert "dfc_screen" in desc, "description does not document the DFC monitor panel"
        assert "dfc_monitor" in desc, "description does not document the component monitor"
        assert "dfc_custom" in desc, "description does not document the custom display"
        assert "dfc_graph" in desc, "description does not document the graph display"
        assert "部件监视器" in desc, "description does not explain the component page"
        assert "自定义显示屏" in desc, "description does not explain the custom display page"
        assert "曲线显示器" in desc, "description does not explain the graph page"
        assert "崩溃告警" in desc, "description does not document the collapse alert"
        assert "离线" in desc, "description does not document the offline state"
        print("      mcmod.info JSON OK (%d char description)" % len(desc))
    except Exception as e:
        print("      mcmod.info INVALID: %r" % (e,))
        return 1

    print("[4/4] scanning final jar for plaintext secrets + required resources ...")
    # 注：以下字面串一旦出现在最终 jar 里，说明防篡改 / 防逆向层被绕过了，兜底扫描报警。
    # 早期还列过一项：旧「指令锁定 /unlock」功能用到的密钥串。那段功能整体移除后，
    # 全项目再无任何代码引用它，留在列表里反而等于把一个疑似账号名永久公开在源码里，故删。
    forbidden = ["gamemode", "unlock", "SHA-256", "integrity check failed", "survival"]
    hits = []
    with zipfile.ZipFile(FINAL_JAR) as z:
        for name in z.namelist():
            if not name.endswith(".class"):
                continue
            data = z.read(name)
            low = data
            for word in forbidden:
                if word.encode("utf-8") in low or word.encode("utf-8").lower() in low:
                    hits.append((name, word))
    if hits:
        print("      FOUND plaintext: %s" % hits[:10])
        return 1
    print("      no plaintext leakage of %s" % forbidden)

    # 物品/创造页是纯资源驱动，漏打包不会报错、只会在游戏里静默丢模型，必须显式断言
    required = [
        "mcmod.info",
        "assets/jiezhimod/models/item/speed_coil.json",
        "assets/jiezhimod/models/item/gravity_coil.json",
        "assets/jiezhimod/models/item/healing_coil.json",
        "assets/jiezhimod/textures/items/speed_coil.png",
        "assets/jiezhimod/textures/items/gravity_coil.png",
        "assets/jiezhimod/textures/items/healing_coil.png",
        "assets/jiezhimod/textures/items/dfc_screen.png",
        "assets/jiezhimod/textures/items/dfc_remote.png",
        # 第二块显示器（部件监视器）：blockstate / item model / 图标 缺一个就是紫黑格
        "assets/jiezhimod/blockstates/dfc_monitor.json",
        "assets/jiezhimod/models/item/dfc_monitor.json",
        "assets/jiezhimod/textures/items/dfc_monitor.png",
        # 第三块显示器（自定义显示屏）：同样三件套
        "assets/jiezhimod/blockstates/dfc_custom.json",
        "assets/jiezhimod/models/item/dfc_custom.json",
        "assets/jiezhimod/textures/items/dfc_custom.png",
        # 第四块显示器（曲线显示器）：同样三件套，缺一个在创造页里就是紫黑格
        "assets/jiezhimod/blockstates/dfc_graph.json",
        "assets/jiezhimod/models/item/dfc_graph.json",
        "assets/jiezhimod/textures/items/dfc_graph.png",
        # 第五块显示器（标注显示器）：配件同样缺一不可
        "assets/jiezhimod/blockstates/dfc_label.json",
        "assets/jiezhimod/models/item/dfc_label.json",
        "assets/jiezhimod/textures/items/dfc_label.png",
        # 第六块显示器（激光状态屏）
        "assets/jiezhimod/blockstates/dfc_laser.json",
        "assets/jiezhimod/models/item/dfc_laser.json",
        "assets/jiezhimod/textures/items/dfc_laser.png",
        # 激光链接器（激光屏专用，v4.3 起不再借遥控器）：模型 / 贴图缺一个就是紫黑格
        "assets/jiezhimod/models/item/laser_connector.json",
        "assets/jiezhimod/textures/items/laser_connector.png",
        # 灯圈 / 灯圈控制台 / 灯圈链接器（v4.4）
        "assets/jiezhimod/blockstates/light_ring.json",
        "assets/jiezhimod/models/block/light_ring.json",
        # 灭态是独立模型 + 独立贴图（闪烁靠它，见下方哨兵）
        "assets/jiezhimod/models/block/light_ring_off.json",
        "assets/jiezhimod/textures/blocks/light_ring_off.png",
        "assets/jiezhimod/models/item/light_ring.json",
        "assets/jiezhimod/textures/blocks/light_ring.png",
        "assets/jiezhimod/blockstates/ring_console.json",
        "assets/jiezhimod/models/block/ring_console.json",
        "assets/jiezhimod/models/item/ring_console.json",
        "assets/jiezhimod/textures/blocks/ring_console.png",
        "assets/jiezhimod/models/item/ring_connector.json",
        "assets/jiezhimod/textures/items/ring_connector.png",
        # GUI 纯色填充用的白纹理。缺了它 GuiDraw 绑不上纹理，
        # 所有自绘面板/格子/进度条都会变成紫黑（或直接看不见）
        "assets/jiezhimod/textures/gui/white.png",
        # 密码盘 / 密码控制台 / 密码连接器：模型与贴图缺一个，游戏里就是紫黑块
        "assets/jiezhimod/blockstates/keypad.json",
        "assets/jiezhimod/models/block/keypad.json",
        "assets/jiezhimod/textures/blocks/keypad.png",
        "assets/jiezhimod/models/item/keypad.json",
        "assets/jiezhimod/textures/items/keypad.png",
        "assets/jiezhimod/blockstates/password_console.json",
        "assets/jiezhimod/models/block/password_console.json",
        "assets/jiezhimod/models/item/password_console.json",
        "assets/jiezhimod/textures/items/password_console.png",
        "assets/jiezhimod/textures/blocks/password_console.png",
        "assets/jiezhimod/models/item/connector.json",
        "assets/jiezhimod/textures/items/connector.png",
        # 调试棒：模型 / 贴图缺一个就是紫黑块
        "assets/jiezhimod/models/item/debug_stick.json",
        "assets/jiezhimod/textures/items/debug_stick.png",
        # 喷漆背包 / 喷气开关：模型与贴图漏打包 -> 物品变成紫黑格，粒子变成"无贴图方块"
        "assets/jiezhimod/models/item/jetpack.json",
        "assets/jiezhimod/models/item/jet_switch.json",
        "assets/jiezhimod/textures/items/jetpack.png",
        "assets/jiezhimod/textures/items/jet_switch.png",
        "assets/jiezhimod/textures/particles/jet_exhaust.png",
        "assets/jiezhimod/lang/en_us.lang",
        "assets/jiezhimod/lang/zh_cn.lang",
        "assets/jiezhimod/sounds.json",
        "assets/jiezhimod/sounds/coil_equip.ogg",
    ]
    # 模型 / blockstates 之间的引用完整性：任何一处对不上，游戏里就是"透明方块"或
    # 紫黑缺模型，而且日志里常常只是一行 warning，极难排查。这里静态全部查一遍。
    res_root = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod")
    json_files = [
        "models/item/speed_coil.json",
        "models/item/gravity_coil.json",
        "models/item/healing_coil.json",
        "models/item/dfc_screen.json",
        "models/item/dfc_remote.json",
        "models/item/dfc_monitor.json",
        "models/item/dfc_custom.json",
        "models/item/dfc_graph.json",
        "blockstates/dfc_screen.json",
        "blockstates/dfc_screen_part.json",
        "blockstates/dfc_monitor.json",
        "blockstates/dfc_custom.json",
        "blockstates/dfc_graph.json",
        "models/item/dfc_label.json",
        "blockstates/dfc_label.json",
        "models/item/dfc_laser.json",
        "blockstates/dfc_laser.json",
        "models/item/laser_connector.json",
        "blockstates/light_ring.json",
        "models/block/light_ring.json",
        "models/block/light_ring_off.json",
        "models/item/light_ring.json",
        "blockstates/ring_console.json",
        "models/block/ring_console.json",
        "models/item/ring_console.json",
        "models/item/ring_connector.json",
        "models/block/dfc_screen_empty.json",
        "models/item/jetpack.json",
        "models/item/jet_switch.json",
    ]
    for rel in json_files:
        fpath = os.path.join(res_root, rel.replace("/", os.sep))
        with open(fpath, encoding="utf-8") as fh:
            json.loads(fh.read())  # 语法错会直接抛，阻断构建

    def _resolve_block_model(ref):
        # 方块状态里的 "model" 引用：原版 Variant.getResourceLocationBlock 无条件在前面拼
        # "block/"（不管引用里有没有斜杠），所以 "jiezhimod:dfc_screen_empty" ->
        # models/block/dfc_screen_empty.json。引用里再写一遍 "block/" 会变成
        # block/block/...（文件找不到 -> 静默透明/紫黑），这里用静态断言拦住。
        name = ref.split(":")[-1]
        return os.path.join(res_root, "models", "block", name.replace("/", os.sep) + ".json")

    def _resolve_parent(ref):
        # 模型里的 "parent" 是完整路径（原样，不再拼前缀）
        name = ref.split(":")[-1]
        return os.path.join(res_root, "models", name.replace("/", os.sep) + ".json")

    # 本模组模型里**引用了原版贴图**（高级红石中继器直接复用 repeater_off / repeater_on /
    # redstone_torch_off / redstone_torch_on / stone_slab_top）。这些 png 在原版 jar 里，
    # 不在本模组的 assets 下，所以只对本模组自己的贴图查文件存在性，
    # 对 minecraft: 前缀的改用白名单（写错名字照样拦得住）。
    VANILLA_TEX_OK = {
        "blocks/repeater_off", "blocks/repeater_on",
        "blocks/redstone_torch_off", "blocks/redstone_torch_on",
        "blocks/stone_slab_top",
    }

    def _resolve_tex(ref):
        name = ref.split(":")[-1]
        return os.path.join(res_root, "textures", name.replace("/", os.sep) + ".png")

    def _tex_ok(ref):
        name = ref.split(":")[-1]
        if ref.startswith("minecraft:"):
            return name in VANILLA_TEX_OK
        return os.path.isfile(_resolve_tex(ref))

    # blockstates -> model（每个方块状态文件都查；漏一个就是"透明/紫黑块"）
    for bs_rel in ("blockstates/dfc_screen.json",
                   "blockstates/dfc_screen_part.json",
                   "blockstates/dfc_monitor.json",
                   "blockstates/dfc_custom.json",
                   "blockstates/dfc_graph.json",
                   "blockstates/advanced_repeater.json"):
        with open(os.path.join(res_root, bs_rel.replace("/", os.sep)), encoding="utf-8") as fh:
            bs = json.load(fh)
        bs_models = [v["model"] for v in bs.get("variants", {}).values()]
        bs_models += [p["apply"]["model"] for p in bs.get("multipart", [])]
        assert bs_models, bs_rel + " has no model at all"
        for m in bs_models:
            assert os.path.isfile(_resolve_block_model(m)), bs_rel + " references missing model: " + m

    # model -> parent / textures
    for rel in json_files:
        fpath = os.path.join(res_root, rel.replace("/", os.sep))
        with open(fpath, encoding="utf-8") as fh:
            data = json.load(fh)
        parent = data.get("parent")
        if parent and ":" in parent and not parent.startswith("minecraft:"):
            assert os.path.isfile(_resolve_parent(parent)), rel + " missing parent: " + parent
        for tex in (data.get("textures") or {}).values():
            if tex.startswith("#"):
                continue  # 变量引用，父模型里解析
            assert _tex_ok(tex), rel + " missing texture: " + tex

    # 语言键缺失时游戏里会直接把 key 名显示出来（如 key.jiezhimod.notice），必须断言
    lang_keys = [
        "key.jiezhimod.notice",
        "key.categories.jiezhimod",
        "gui.jiezhimod.notice.title",
        "gui.jiezhimod.notice.hint",
        "gui.jiezhimod.notice.empty",
        "tile.dfc_screen.name",
        "tile.dfc_monitor.name",
        "tile.dfc_custom.name",
        "tile.dfc_graph.name",
        "tile.keypad.name",
        "tile.password_console.name",
        "item.connector.name",
        "item.debug_stick.name",
        "gui.jiezhimod.custom.title",
        "gui.jiezhimod.custom.power.on",
        "gui.jiezhimod.custom.power.off",
        "gui.jiezhimod.custom.delete",
        "gui.jiezhimod.custom.clear",
        "gui.jiezhimod.custom.close",
        "gui.jiezhimod.custom.empty",
        "gui.jiezhimod.custom.offline",
        "gui.jiezhimod.custom.hint",
        "itemGroup.jiezhimod_screen",
        "item.dfc_remote.name",
        "itemGroup.jiezhimod",
        "itemGroup.jiezhimod_redstone",
        "tile.advanced_repeater.name",
        "gui.jiezhimod.repeater.title",
        "gui.jiezhimod.repeater.unit.tick",
        "gui.jiezhimod.repeater.unit.second",
        "gui.jiezhimod.repeater.apply",
        "tooltip.jiezhimod.advanced_repeater.0",
        "tooltip.jiezhimod.advanced_repeater.3",
        "tile.dfc_label.name",
        "tooltip.jiezhimod.dfc_label.0",
        "tooltip.jiezhimod.dfc_label.4",
        "gui.jiezhimod.debug.label.hint",
        "gui.jiezhimod.debug.label.reset",
        "gui.jiezhimod.debug.label.refresh",
        "tile.dfc_laser.name",
        "tooltip.jiezhimod.dfc_laser.0",
        "tooltip.jiezhimod.dfc_laser.4",
        # ---- v4.3：激光链接器 + 激光屏独立面板 ----
        "item.laser_connector.name",
        "tooltip.jiezhimod.laser_connector.0",
        "tooltip.jiezhimod.laser_connector.4",
        "gui.jiezhimod.laser.add",
        "gui.jiezhimod.laser.connector",
        "gui.jiezhimod.laser.empty",
        "gui.jiezhimod.laser.offline",
        "gui.jiezhimod.laser.hint",
        # ---- v4.4：灯圈 / 灯圈控制台 / 灯圈链接器 ----
        "tile.light_ring.name",
        "tile.ring_console.name",
        "item.ring_connector.name",
        "tooltip.jiezhimod.ring_connector.0",
        "gui.jiezhimod.ring.title",
        "gui.jiezhimod.ring.act.color",
        "gui.jiezhimod.ring.act.blink",
        "gui.jiezhimod.ring.mode.fade",
        "gui.jiezhimod.ring.blink.period",
        "gui.jiezhimod.ring.bind.none",
        "gui.jiezhimod.ring.hint",
    ]
    for lang in ("en_us", "zh_cn"):
        path = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod", "lang", lang + ".lang")
        with open(path, encoding="utf-8") as fh:
            body = fh.read()
        for key in lang_keys:
            if key not in body:
                print("      MISSING lang key %r in %s.lang" % (key, lang))
                return 1
    # sounds.json 缺文件或 key 对不上时，客户端只是静默不发声，同样要断言
    with open(os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod", "sounds.json"),
              encoding="utf-8") as fh:
        sounds = json.load(fh)
    if "coil_equip" not in sounds:
        print("      sounds.json missing 'coil_equip' entry")
        return 1
    ogg = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod", "sounds", "coil_equip.ogg")
    with open(ogg, "rb") as fh:
        magic = fh.read(4)
    if magic != b"OggS":
        print("      coil_equip.ogg is not an Ogg stream (magic=%r)" % (magic,))
        return 1
    with zipfile.ZipFile(FINAL_JAR) as z:
        names = set(z.namelist())
        missing = [r for r in required if r not in names]
    if missing:
        print("      MISSING resources in jar: %s" % missing)
        return 1
    print("      all %d required resources packaged" % len(required))

    # ---- v4.3：激光屏独立面板 / 激光链接器 / 中继器朝向 的源码哨兵 ----------
    # 【为什么要在源码层面再钉一遍】这三件事的共同点是"编译能过、运行不报错、
    # 但功能就是没有"：
    #   · 激光屏若又落回 GUI_CUSTOM_SCREEN，界面就变回自定义屏那个 8 行面板；
    #   · 激光屏若继续读遥控器的 NBT 键，「添加」永远读不到东西；
    #   · 中继器的 FACING 语义只要有一处和原版不一致，实机就是"喂不进信号/不输出"。
    def _src(rel):
        with io.open(os.path.join(java_src_probe, rel), encoding="utf-8") as fh:
            return fh.read()

    def _strip_java_comments(text):
        """哨兵查的是"代码里有没有真的这么写"，注释里解释这个坑不该算违规。

        这个坑本身必须在源码注释里讲清楚（否则下一个人又会搬回 onModelRegistry），
        所以扫之前先把 // 与 /* */ 剥掉，否则"说明文字"会被当成"违规调用"。
        """
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        return re.sub(r"//[^\n]*", "", text)

    java_src_probe = os.path.join(ROOT, "src", "main", "java")
    multiblock = _src("com/jiezhimod/block/ScreenMultiblock.java")
    # 激光屏是 TileCustomScreen 的子类，必须**先**判它，后判自定义屏
    i_laser = multiblock.find("instanceof TileLaserScreen")
    i_custom = multiblock.find("instanceof TileCustomScreen")
    if i_laser < 0 or i_custom < 0 or i_laser > i_custom:
        print("      ScreenMultiblock: 必须先判 TileLaserScreen 再判 TileCustomScreen"
              "（否则激光屏会开成自定义屏那个 8 行面板）")
        return 1
    if "GUI_LASER_SCREEN" not in multiblock:
        print("      ScreenMultiblock: 右键激光屏没有打开 GUI_LASER_SCREEN")
        return 1

    for proxy in ("com/jiezhimod/proxy/CommonProxy.java", "com/jiezhimod/proxy/ClientProxy.java"):
        body = _src(proxy)
        if "GUI_LASER_SCREEN" not in body:
            print("      %s: 没有处理 GUI_LASER_SCREEN（界面打不开）" % proxy)
            return 1
    if "ContainerLaserScreen" not in _src("com/jiezhimod/proxy/CommonProxy.java"):
        print("      CommonProxy: GUI_LASER_SCREEN 没有对应的容器（客户端开不出界面）")
        return 1
    if "GuiLaserScreen" not in _src("com/jiezhimod/proxy/ClientProxy.java"):
        print("      ClientProxy: GUI_LASER_SCREEN 没有对应的 GUI")
        return 1

    # 激光屏必须覆盖 importDevices（父类读的是遥控器的键，读链接器读不到）
    laser_tile = _src("com/jiezhimod/block/TileLaserScreen.java")
    if "int[] importDevices(" not in laser_tile:
        print("      TileLaserScreen 没覆盖 importDevices：点「添加」永远接不进设备")
        return 1
    if "ItemLaserConnector" not in laser_tile:
        print("      TileLaserScreen.importDevices 没有读激光链接器")
        return 1

    # 面板行数：界面必须按 LaserLayout.SLOTS 画，不许再用 DfcWatch.MAX 那个 8
    gui_laser = _src("com/jiezhimod/client/gui/GuiLaserScreen.java")
    if "LaserLayout.SLOTS" not in gui_laser:
        print("      GuiLaserScreen: 行数没有走 LaserLayout.SLOTS（会又画成 8 行）")
        return 1
    if "DfcWatch.MAX" in gui_laser:
        print("      GuiLaserScreen: 还在用 DfcWatch.MAX（8 条）当行数")
        return 1

    # 中继器：四条口径必须整套照原版（混着写就是"不输出信号"）
    repeater = _src("com/jiezhimod/block/BlockAdvancedRepeater.java")
    if "getHorizontalFacing().getOpposite()" not in repeater:
        print("      BlockAdvancedRepeater: 放置朝向不是原版的 getOpposite()")
        return 1
    if "BlockPos src = pos.offset(facing);" not in repeater:
        print("      BlockAdvancedRepeater: hasInput 读的不是 FACING 那一面"
              "（输入/输出会同侧 = 永远不输出）")
        return 1
    if "pos.offset(facing.getOpposite())" in repeater:
        print("      BlockAdvancedRepeater: hasInput 又改回读 FACING 的反面了")
        return 1
    if "state.getValue(FACING) == side ? 15 : 0" not in repeater:
        print("      BlockAdvancedRepeater: getWeakPower 的侧别不是原版口径")
        return 1

    # ---- 中继器：延时记忆 + 两个指示灯（v4.4 实机反馈） ----
    # ① 计划刻只是兜底，不许抄近路把延时踩掉；
    #    实机症状正是"设好的延时用一次就没了"。
    rep_tile = _src("com/jiezhimod/block/TileAdvancedRepeater.java")
    if "this.timer.elapsed() >= this.delay - 1" not in rep_tile:
        print("      TileAdvancedRepeater: 计划刻分支没有「等满才对齐」的判断"
              "（会把设好的延时抄近路跳过）")
        return 1
    # ② NBT 缺键时不能用 0 顶替（clamp(0)=1 tick，等于把延时偷偷改成 0.05 秒）
    if "compound.hasKey(TAG_DELAY)" not in rep_tile:
        print("      TileAdvancedRepeater: readFromNBT 缺键时会把延时改成 1 tick")
        return 1
    # ③ 输入火把要有独立的方块状态位
    if "PropertyBool.create(\"input\")" not in repeater:
        print("      BlockAdvancedRepeater: 没有 input 状态位（输入侧火把点不亮）")
        return 1
    _bs = io.open(os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod",
                               "blockstates", "advanced_repeater.json"), encoding="utf-8").read()
    _n = _bs.count('"model"')
    if _n != 32:
        print("      blockstates/advanced_repeater.json: 变体数 %d，应为 4 朝向 x 8 组合 = 32"
              % _n)
        return 1
    # 8 个 (input, locked, powered) 组合必须齐全 —— 用解析而不是子串匹配，
    # 这样检查本身不会绑死在 key 的书写顺序上（顺序另有一条哨兵专门管）。
    _combos = set()
    for _k in json.loads(_bs)["variants"].keys():
        _d = dict(seg.split("=") for seg in _k.split(","))
        _combos.add((_d.get("input"), _d.get("locked"), _d.get("powered")))
    if len(_combos) != 8:
        print("      blockstates/advanced_repeater.json: (input,locked,powered) 组合只有 %d 种，应为 8"
              % len(_combos))
        return 1

    # ---- ★★ variant key 的属性顺序必须是属性名字母序（v4.4 中继器紫黑格的真根因）----
    # BlockStateContainer 构造时按属性名排序生成 propertiesList，
    # ModelResourceLocation 的 variant 字符串就是照它拼的；blockstates 的 key 是
    # **原样字符串直接查表**。顺序不一致 ⇒ MissingVariantException ⇒ 整块变紫黑棋盘格。
    # 表现：日志里 "Exception loading model for variant jiezhimod:advanced_repeater#..."
    #       + "Caused by: ModelBlockDefinition$MissingVariantException"。
    for _k in json.loads(_bs)["variants"].keys():
        _names = [seg.split("=")[0] for seg in _k.split(",")]
        if _names != sorted(_names):
            print("      blockstates/advanced_repeater.json: variant key 属性顺序不是字母序：")
            print("        %s  →  应为 %s" % (_k, ",".join(sorted(_names))))
            print("      （BlockStateContainer 按属性名排序拼 key，顺序错了就查不到 → 紫黑格）")
            return 1
    for _m in ("advanced_repeater_off", "advanced_repeater_in", "advanced_repeater_on",
               "advanced_repeater_both", "advanced_repeater_off_locked",
               "advanced_repeater_in_locked", "advanced_repeater_on_locked",
               "advanced_repeater_both_locked"):
        _p = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod",
                          "models", "block", _m + ".json")
        if not os.path.exists(_p):
            print("      缺少中继器模型 %s.json（指示灯少一态就少一个文件）" % _m)
            return 1

    # ---- ★★ 灯圈 tint 的注册时机（v4.4 "灯圈永不变色"的真正根因）----
    # 原版 Minecraft 构造函数：467 beginMinecraftLoading→preInit、514 BlockColors.create()
    # （内部就 post ColorHandlerEvent.Block）、535 finishMinecraftLoading→init。
    # 也就是说颜色事件在 **init 之前** 就发完了 —— 监听器必须挂在 preInit 里。
    _cp = _strip_java_comments(_src("com/jiezhimod/proxy/ClientProxy.java"))
    _i_pre = _cp.find("public void preInit()")
    _i_init = _cp.find("public void init()")
    _i_reg = _cp.find("RingColorHandlers()")
    if _i_reg < 0:
        print("      ClientProxy: 没有注册 RingColorHandlers（灯圈永远是贴图原色）")
        return 1
    if not (_i_pre >= 0 and _i_pre < _i_reg < _i_init):
        print("      ClientProxy: RingColorHandlers 必须在 preInit() 里注册 ——"
              " ColorHandlerEvent 在 Minecraft 构造的第 514 行（init 在第 535 行）就发完了，"
              " 放 init 里等于永远收不到")
        return 1

    # ---- ★★ 灯圈亮 / 灭必须是两套模型（闪烁不能只靠 tint 压暗）----
    # tint 只在区块重烘焙时采样；而且 1.12.2 的方块光只有亮度没有颜色，
    # 白天户外"光没了"几乎看不出来。原版红石灯的做法就是亮/灭两张贴图，
    # 靠方块状态直接切模型 —— 服务端一翻 POWERED，客户端立刻换面，必定可见。
    _lr_bs_p = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod",
                            "blockstates", "light_ring.json")
    _lr_bs = json.load(io.open(_lr_bs_p, encoding="utf-8"))["variants"]
    if len(_lr_bs) != 16:
        print("      blockstates/light_ring.json: 变体数 %d，应为 16 档（level=0~15）"
              % len(_lr_bs))
        return 1
    _lr_off = _lr_bs.get("level=0", {}).get("model")
    _lr_on = _lr_bs.get("level=15", {}).get("model")
    if not _lr_off or not _lr_on:
        print("      blockstates/light_ring.json: 缺少 level=0 / level=15（亮灭必须分开）")
        return 1
    if _lr_off == _lr_on:
        print("      blockstates/light_ring.json: 亮/灭指向同一个模型 ——"
              " 灭态就只能靠 tint 压暗，而 tint 只在重烘焙时采样，闪烁会看不见")
        return 1
    for _lv in range(1, 16):
        if _lr_bs.get("level=%d" % _lv, {}).get("model") != _lr_on:
            print("      blockstates/light_ring.json: level=%d 没有指向亮态模型" % _lv)
            return 1
    _lr_off_json = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod",
                                "models", "block",
                                _lr_off.split(":")[-1] + ".json")
    if not os.path.exists(_lr_off_json):
        print("      缺少灯圈灭态模型 %s" % os.path.basename(_lr_off_json))
        return 1
    if '"tintindex"' in io.open(_lr_off_json, encoding="utf-8").read():
        print("      灯圈灭态模型里不该有 tintindex（灭态必须完全不依赖 tint 采样时机）")
        return 1

    # ---- ★★ displayColor() 必须只读 ----
    # 它被 IBlockColor 调用（烘焙时）。在里面写回 displayColor 会让
    # clientTick 的"颜色变了没有"判据永远为假 ⇒ 再也不会请求重烘焙 ⇒ 画面卡住。
    _tlr = _strip_java_comments(_src("com/jiezhimod/block/TileLightRing.java"))
    _dc_i = _tlr.find("public int displayColor()")
    if _dc_i < 0:
        print("      TileLightRing: 找不到 displayColor()")
        return 1
    _dc_body = _tlr[_dc_i:_tlr.find("\n    }", _dc_i)]
    if "this.displayColor =" in _dc_body:
        print("      TileLightRing.displayColor(): 不许写回 this.displayColor ——"
              " tint 每次烘焙都会调它，写回会让 clientTick 判不出颜色变化，"
              " 结果就是再也不请求重烘焙（淡入淡出/闪烁全停住）")
        return 1


    # ---- 灯圈周围的彩色光晕（原版方块光没有颜色通道，只能自己画）----
    _glow_p = os.path.join(ROOT, "src", "main", "java", "com", "jiezhimod",
                           "client", "ring", "RingGlowRenderer.java")
    if not os.path.exists(_glow_p):
        print("      缺少 RingGlowRenderer：灯圈周围就只剩白色的原版方块光"
              "（1.12 的方块光只有亮度、没有颜色通道）")
        return 1
    _glow = _strip_java_comments(io.open(_glow_p, encoding="utf-8").read())
    if "RingGlowRenderer()" not in _cp:
        print("      ClientProxy: 没有注册 RingGlowRenderer（光晕不会画）")
        return 1
    if "import net.optifine" in _glow:
        print("      RingGlowRenderer: 不许直接 import net.optifine ——"
              " OptiFine 常常是启动器层 tweaker（不在 mods 里），直接引用会在没装时"
              " NoClassDefFoundError 把整个渲染钩子带崩；必须 Class.forName 反射")
        return 1
    if "RenderWorldLastEvent" not in _glow:
        print("      RingGlowRenderer: 必须挂 RenderWorldLastEvent（每帧世界渲染末尾）")
        return 1
    if _tlr.count("CLIENT_ALL") < 3:
        print("      TileLightRing: 必须维护客户端灯圈登记表（onLoad 加入 / invalidate 移除），"
              " 否则光晕渲染器每帧都要扫区块")
        return 1


    # ---- 旋转警示灯（v4.5）----
    _al = _strip_java_comments(_src("com/jiezhimod/block/BlockAlarmLight.java"))
    _alt = _strip_java_comments(_src("com/jiezhimod/block/TileAlarmLight.java"))
    _alc = _strip_java_comments(_src("com/jiezhimod/block/BlockAlarmConsole.java"))
    _alr = _strip_java_comments(_src("com/jiezhimod/client/render/RenderAlarmLight.java"))

    # 世界里必须 INVISIBLE：留 cube 占位的话，方块边界会把圆罩和光锥一起罩住
    if "EnumBlockRenderType.INVISIBLE" not in _al:
        print("      BlockAlarmLight: 世界里必须是 INVISIBLE 渲染类型 ——"
              " 留一个 cube 占位会把圆灯罩和光锥全罩在方块里，'完美的圆'就没了")
        return 1
    # 六向朝向：只能是 6 个方向的 PropertyEnum，别退回 PropertyHorizontal
    if "PropertyEnum.create(\"facing\", EnumFacing.class)" not in _al:
        print("      BlockAlarmLight: FACING 必须是六向的 PropertyEnum<EnumFacing>"
              "（要能贴天花板 / 地板 / 墙，只做水平四面就不满足'任意方向摆放'）")
        return 1
    # ★ 改颜色必须自己发 TE 包
    if "broadcastState" not in _alt or "getUpdatePacket" not in _alt:
        print("      TileAlarmLight: 改配置必须 broadcastState()（自己发 SPacketUpdateTileEntity）——"
              " notifyBlockUpdate 只带方块状态、不带 TE 的 NBT，客户端颜色永远不会变")
        return 1
    # 控制台：两个 TE 入口都要写（祖先覆写了 createTileEntity 的整类坑）
    if "createTileEntity" not in _alc or "createNewTileEntity" not in _alc:
        print("      BlockAlarmConsole: createTileEntity 与 createNewTileEntity 必须都覆写 ——"
              " 1.12.2 实际调前者，只写后者会静默失效（docs/踩坑记录 #17）")
        return 1
    # TESR 必须注册，且不许漏 disableCull（圆罩要双面）
    if "RenderAlarmLight" not in _cp:
        print("      ClientProxy: 没有绑定 RenderAlarmLight（方块是 INVISIBLE，不绑就等于空气）")
        return 1

    # ---- 入游提示（v5.2.1）----
    _wn_path = os.path.join(java_src_probe, "com", "jiezhimod", "client", "WelcomeNotice.java")
    if not os.path.isfile(_wn_path):
        print("      WelcomeNotice.java 不见了：入游提示是玩家确认「模组真的装上了」的唯一信号")
        return 1
    _wn = _strip_java_comments(io.open(_wn_path, encoding="utf-8").read())
    if "WelcomeNotice" not in _cp:
        print("      ClientProxy: 没有注册 WelcomeNotice —— 事件监听器不注册就永远不会触发")
        return 1
    if "TextComponentString" not in _wn:
        print("      WelcomeNotice: 必须用原版 TextComponentString 拼消息（别手搓 JSON）")
        return 1
    if "ClientConnectedToServerEvent" not in _wn or "armed" not in _wn:
        print("      WelcomeNotice: 必须 ClientConnectedToServerEvent 上膛 + armed 卸膛 —— "
              "直接监听 EntityJoinWorldEvent 会被每个实体触发，换维度也会重发")
        return 1
    if "JiezhiMod.VERSION" not in _wn:
        print("      WelcomeNotice: 版本号必须读 JiezhiMod.VERSION，不许另写一份字面量")
        return 1

    # ---- v5.0.0 地/墙/顶三向光源：LightType 是唯一分类器 ----
    _lt_path = os.path.join(java_src_probe, "com", "jiezhimod", "client", "render",
                            "LightType.java")
    if not os.path.isfile(_lt_path):
        print("      LightType.java 不见了：地/墙/顶三种安装方式必须是同一个枚举判，"
              " 散在各处写 \"axis.y 比较\" 一定会漂")
        return 1
    _lt = _strip_java_comments(io.open(_lt_path, encoding="utf-8").read())
    for _need in ("FLOOR", "WALL", "CEILING", "beamCount", "AXIS_Y_THRESHOLD",
                  "cosInner", "cosOuter", "range"):
        if _need not in _lt:
            print("      LightType 缺 %s —— 安装方式 / 光束条数 / 光锥 / 射程必须都靠它，"
                  " 别在别处另写一份" % _need)
            return 1
    # 三档光锥：内角必须 < 外角；且立杆与挂壁的锥角必须不同
    # （挂壁退回立杆的 55° 外角 = 光锥变喇叭罩住整面墙，v5.0.0-beta 实测）。
    # ★ v5.2.2 起挂壁=45/75/10 与吊顶=45/75/10 **可以相同**：两者都是"用锥体外缘去贴表面"，
    #   一个贴墙、一个贴地，几何同构，宽锥是合理巧合，不再要求三档两两互异。
    _cones = re.findall(r"FLOOR\(([0-9.]+)F, ([0-9.]+)F, ([0-9.]+)F",
                        _lt) + re.findall(r"WALL\(([0-9.]+)F, ([0-9.]+)F, ([0-9.]+)F", _lt) \
        + re.findall(r"CEILING\(([0-9.]+)F, ([0-9.]+)F, ([0-9.]+)F", _lt)
    if len(_cones) != 3:
        print("      LightType: 找不到完整的三档光锥/射程构造")
        return 1
    if not all(float(c[0]) < float(c[1]) for c in _cones):
        print("      LightType: 每一档都要求 内角 < 外角（cos 才是 inner > outer）")
        return 1
    if _cones[0] == _cones[1]:
        print("      LightType: 立杆与挂壁的光锥/射程不能相同 —— "
              "挂壁用立杆的 55° 外角就是\"光锥变喇叭罩住整面墙\"（v5.0.0-beta）")
        return 1
    # ★★★ 光束条数（v5.4.7）：挂壁改回单束，只有立杆还是 2 条对扫
    if "FLOOR ? 2 : 1" not in _lt:
        print("      LightType.beamCount 必须是 `this == FLOOR ? 2 : 1` —— "
              " v5.4.7 起挂壁只发 1 束（双束会让光左右飞出视野），只有立杆还是 2 条")
        return 1
    # ★★★ MAX_LIGHTS 与着色器数组长度必须一致（v5.2.0 扩到 8）
    #   两边对不上时 GL 不会报错：少的那一侧多出来的光源会被静默丢弃/读到垃圾，
    #   表现是"灯数一多就有几盏不亮"，且没有任何日志。
    _maxl = re.search(r"MAX_LIGHTS\s*=\s*(\d+)\s*;",
                      _strip_java_comments(_src("com/jiezhimod/client/render/AlarmSpotlights.java")))
    _ddp_raw = _src("com/jiezhimod/client/render/DepthDebugPass.java")
    _shader_arr = re.findall(r"uniform vec[34]\s+uLight\w+\[(\d+)\]", _ddp_raw)
    _shader_loop = re.search(r"for \(int i = 0; i < (\d+); i\+\+\)", _ddp_raw)
    if not _maxl:
        print("      AlarmSpotlights: 找不到 MAX_LIGHTS 常量")
        return 1
    _ml = _maxl.group(1)
    if not _shader_arr:
        print("      DepthDebugPass: 着色器里找不到 uLightPos[N] 等 uniform 数组声明")
        return 1
    if set(_shader_arr) != {_ml}:
        print("      MAX_LIGHTS=%s 与着色器 uniform 数组长度 %s 不一致 —— "
              "多出来的光源会被静默丢弃，且不会有任何报错"
              % (_ml, sorted(set(_shader_arr))))
        return 1
    if not _shader_loop or _shader_loop.group(1) != _ml:
        print("      MAX_LIGHTS=%s 与着色器循环上限 %s 不一致 —— "
              "循环跑不够时后面的光源权重永远是 0（灯亮着但没光）"
              % (_ml, _shader_loop.group(1) if _shader_loop else "找不到"))
        return 1

    _asp = _strip_java_comments(_src("com/jiezhimod/client/render/AlarmSpotlights.java"))
    if "isWallMounted" in _asp or "isWallMounted" in _alr:
        print("      v5.0.0 起安装方式只走 LightType.classify(axis)，不要再出现 isWallMounted"
              "（两个判据并存必然对不上，v4.22 就是这么出的怪光）")
        return 1
    # ★★★ 全局共享锥角的旧写法必须绝迹：三种姿态共用 55° 外角就是"喇叭"bug
    for _bad in ("COS_INNER", "COS_OUTER", "CONE_INNER_DEG", "CONE_OUTER_DEG"):
        if _bad in _asp:
            print("      AlarmSpotlights 里还有全局 %s —— 光锥必须按 LightType 分档"
                  "（立杆 35/55、挂壁 45/75、吊顶 45/75）" % _bad)
            return 1
    # ★★★ 近距命中：挂壁"端盖平面"截断、立杆/吊顶忽略命中；绝不允许整束关闭
    if "hitDist < MIN_CLEAR_DIST" not in _asp:
        print("      AlarmSpotlights.trace: 少了 hitDist < MIN_CLEAR_DIST 判定 —— "
              " 近处方块会生成无限半空间，把整片光斑切成方块（v4.22 实测）")
        return 1
    if "Math.max(hitDist, WALL_CAP_MIN)" not in _asp:
        print("      AlarmSpotlights.trace: 近距命中必须用\"端盖平面\""
              "（Math.max(hitDist, WALL_CAP_MIN)）截断，不是压缩射程 —— "
              "5.0.1~5.1.1 压射程把光钉在灯口 1 格内，实机就是\"光变成贴在墙上的亮盘\"")
        return 1
    # 压缩射程的两个常量必须绝迹：range 还兼管渐隐曲线，压它等于同时缩小光斑
    for _bad in ("WALL_TRUNC_PAD", "WALL_MIN_RANGE"):
        if _bad in _asp:
            print("      AlarmSpotlights 里还有 %s —— 射程只许按 LightType 定档，"
                  "近距遮挡必须交给端盖平面（range 同时决定渐隐曲线，压它会连带缩小光斑）"
                  % _bad)
            return 1
    if "return false" in _asp:
        print("      AlarmSpotlights: 不要再出现 return false —— v5.0.0 用\"近距整束关闭\""
              "导致贴墙的挂壁灯隐身，现在改为端盖截断 / 忽略命中")
        return 1
    # 端盖下限不能贴到灯口：灯塞进一格深的凹槽时端盖若跟着贴脸，等于整盏不发光
    _cap = re.search(r"WALL_CAP_MIN\s*=\s*([0-9.]+)D", _asp)
    if not _cap:
        print("      AlarmSpotlights: 找不到 WALL_CAP_MIN 常量")
        return 1
    if float(_cap.group(1)) < 1.0:
        print("      AlarmSpotlights.WALL_CAP_MIN = %s < 1.0：端盖会贴到灯口，"
              "灯塞在凹槽里时等于整盏灯不发光（v5.0.0 隐身 bug 的另一种写法）"
              % _cap.group(1))
        return 1
    # ★★★ 挂壁灯方向：单束完整贴墙圆环 + 下移（v5.4.9 定案，哨兵已精简）
    _wall_body = _alr[_alr.index("case WALL: {"):]
    _wall_body = _wall_body[:_wall_body.index("case CEILING: {")]
    # 校验：radius*cos（水平画圆）与 radius*sin（竖直画圆）必须成对
    if "radius * Math.cos(ang)" not in _wall_body:
        print("      beamWorldDir 的 WALL 分支没有 `radius * Math.cos(ang)`："
              "墙面画圆必须有水平分量，否则光只会上下扫、全打在地上（v5.4.9）")
        return 1
    if "radius * Math.sin(ang)" not in _wall_body:
        print("      beamWorldDir 的 WALL 分支没有 `radius * Math.sin(ang)`："
              "sin 必须与 cos 成对，构成完整圆环（v5.4.9）")
        return 1
    # v5.4.9：yOffset 重新启用（光斑中心下移 1 格）
    if not re.search(r"double\s+yOffset\s*=\s*([0-9.]+)D", _wall_body):
        print("      beamWorldDir 的 WALL 分支缺 yOffset：光斑中心必须下移，"
              "圆心落在灯下方（v5.4.9）")
        return 1
    # 仅保留一条防回归：v5.4.6 的 WALL_SPOT_Y 下压写法已废弃（v5.4.9 用 yOffset）
    if "WALL_SPOT_Y" in _alr:
        print("      RenderAlarmLight 里还有 WALL_SPOT_Y —— v5.4.6 的下压写法已废弃，"
              "v5.4.9 起用 yOffset")
        return 1
    # 吊装灯必须严格垂直向下，不许有横向扫描
    _cr = re.search(r"CEILING_RADIAL\s*=\s*([0-9.]+)D", _alr)
    if not _cr:
        print("      RenderAlarmLight 缺 CEILING_RADIAL：吊装分支没有明确声明横向分量")
        return 1
    if float(_cr.group(1)) != 0.0:
        print("      RenderAlarmLight.CEILING_RADIAL = %s ≠ 0：吊顶灯必须是垂直向下"
              "（dir.y = -1、零横向扫描），斜出去会打在墙面和旁边的机器上" % _cr.group(1))
        return 1

    # blockstates 的 key 顺序（和 attributes 一样，必须字母序 facing < on）
    _al_bs = json.load(io.open(os.path.join(ROOT, "src", "main", "resources", "assets",
                                            "jiezhimod", "blockstates",
                                            "alarm_light.json"), encoding="utf-8"))["variants"]
    if len(_al_bs) != 12:
        print("      blockstates/alarm_light.json: 变体数 %d，应为 6 朝向 x 2 开关 = 12"
              % len(_al_bs))
        return 1
    for _k in _al_bs.keys():
        _names = [seg.split("=")[0] for seg in _k.split(",")]
        if _names != sorted(_names):
            print("      blockstates/alarm_light.json: variant key 顺序不是字母序：%s" % _k)
            return 1

    # ---- 警示灯 ← 显示屏 告警联动（v4.5）----
    # 这条链断了不会有任何报错：灯照常亮、照常转，只是永远一个颜色。
    _rules_p = os.path.join(ROOT, "src", "main", "java", "com", "jiezhimod",
                            "alarm", "AlertRules.java")
    if not os.path.exists(_rules_p):
        print("      缺少 AlertRules：警示灯没法按告警等级变色")
        return 1
    _rules = _strip_java_comments(io.open(_rules_p, encoding="utf-8").read())
    for _kk in ('"hot"', '"melt"', '"collapse"', '"detonation"', '"laser"'):
        if _kk not in _rules:
            print("      AlertRules: 少了对 %s 的判定（温度 / 熔点 / 熔毁 / 引爆 / 激光部件）"
                  % _kk)
            return 1
    if "COLOR_WARN" not in _rules or "COLOR_HIGH" not in _rules or "COLOR_CRIT" not in _rules:
        print("      AlertRules: 四级配色必须都在（黄 / 橙 / 红）")
        return 1

    # 灯必须自己去读屏幕：屏幕不会向灯推数据
    if "ITickable" not in _alt:
        print("      TileAlarmLight: 必须 implements ITickable ——"
              " 告警等级要靠 update() 每 10 刻轮询一次，不注册 tick 就永远不判定")
        return 1
    if "TileDfcScreen" not in _alt or "AlertRules.evaluate" not in _alt:
        print("      TileAlarmLight: 必须从绑定的 TileDfcScreen 取 getData() 并交给"
              " AlertRules.evaluate 判定")
        return 1
    if "applyConsoleConfig" not in _alt:
        print("      TileAlarmLight: 必须有 applyConsoleConfig —— 联动中的灯要忽略控制台推送，"
              " 否则告警色刚亮就被刷回统一色")
        return 1
    _alct = _strip_java_comments(_src("com/jiezhimod/block/TileAlarmConsole.java"))
    if "applyConsoleConfig" not in _alct:
        print("      TileAlarmConsole: 推配置必须走 applyConsoleConfig（不是 applyConfig）"
              " —— 这里读的是 TileAlarmConsole（方块类里没有这个方法）")
        return 1

    # 链接器：必须能记录"数据源"并把它绑到灯上
    _conn = _strip_java_comments(_src("com/jiezhimod/item/ItemAlarmConnector.java"))
    if "writeSource" not in _conn or "readSource" not in _conn:
        print("      ItemAlarmConnector: 必须能记录告警数据源（蹲下右键显示屏）")
        return 1
    if "bindSource" not in _conn:
        print("      ItemAlarmConnector: 记录数据源之后必须调 TileAlarmLight.bindSource")
        return 1

    # 告警文案：中英语言文件必须一一对应，否则玩家看到的是原始 key
    _zh = io.open(os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod",
                               "lang", "zh_cn.lang"), encoding="utf-8").read()
    _en = io.open(os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod",
                               "lang", "en_us.lang"), encoding="utf-8").read()
    for _kk in ("ok", "hot", "melt", "collapse", "detonation", "destroyed", "stab",
                "laser", "off", "nosrc", "level.0", "level.1", "level.2", "level.3"):
        _lang_key = "alert.jiezhimod." + _kk + "="
        if _lang_key not in _zh or _lang_key not in _en:
            print("      语言文件缺少 %s（中英都要有）" % _lang_key[:-1])
            return 1

    # ---- 中继器模型：faces.texture 必须写 #变量 ----
    # 写成资源路径（minecraft:blocks/xxx）时 ModelBlock.resolveTextureName 会给它补 '#'，
    # 查不到就整个模型加载失败 —— 游戏里表现为"方块变紫黑格"。
    _rep_models = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod",
                               "models", "block")
    for _fn in sorted(os.listdir(_rep_models)):
        if not _fn.startswith("advanced_repeater") or not _fn.endswith(".json"):
            continue
        _m = json.load(io.open(os.path.join(_rep_models, _fn), encoding="utf-8"))
        _tex = _m.get("textures", {})
        for _el in _m.get("elements", []):
            for _face, _def in _el.get("faces", {}).items():
                _t = _def.get("texture", "")
                if not _t.startswith("#"):
                    print("      %s 的 %s 面直接写了贴图路径 %s（必须是 #变量名，否则模型加载失败变紫黑格）"
                          % (_fn, _face, _t))
                    return 1
                if _t[1:] not in _tex:
                    print("      %s 引用了未定义的贴图变量 %s" % (_fn, _t))
                    return 1

    # ---- 灯圈控制台 GUI：开关互斥 / 无应用按钮 / 预设色自绘 ----
    _rgui2 = _strip_java_comments(_src("com/jiezhimod/client/gui/GuiRingConsole.java"))
    if "&= ~RingConsoleConfig.BIT_OFF" not in _rgui2 or "&= ~RingConsoleConfig.BIT_ON" not in _rgui2:
        print("      GuiRingConsole: 开启 / 关闭没有互斥（同时选没有意义）")
        return 1
    if "ID_APPLY" in _rgui2:
        print("      GuiRingConsole: 又出现「应用」按钮了（v4.4 起改成输入即保存）")
        return 1
    if "presetBox" not in _rgui2 or "GuiDraw.drawRect(b[0], b[1]" not in _rgui2:
        print("      GuiRingConsole: 预设色块不是自绘的（用 GuiButton 会被底纹盖成灰色）")
        return 1

    # ---- 灯圈控制台：配置必须能回到客户端 ----
    # world.notifyBlockUpdate 只发方块状态、不带方块实体 NBT（本项目反复踩），
    # 所以配置/绑定/上次触发必须自己发包，否则重开界面全是默认值。
    ring_console_src = _strip_java_comments(_src("com/jiezhimod/block/TileRingConsole.java"))
    if "notifyBlockUpdate" in ring_console_src:
        print("      TileRingConsole: 又用 notifyBlockUpdate 同步配置了"
              "（它不带方块实体 NBT，客户端永远是默认配置）")
        return 1
    if "MessageRingConsoleSync" not in ring_console_src:
        print("      TileRingConsole: 没有把自己的状态发给客户端")
        return 1
    _net = _src("com/jiezhimod/network/ModNetwork.java")
    if "MessageRingConsoleSync" not in _net:
        print("      ModNetwork: MessageRingConsoleSync 没注册")
        return 1

    # ---- 灯圈控制台 GUI：切换闪烁方式必须同时改输入框 ----
    _rgui = _src("com/jiezhimod/client/gui/GuiRingConsole.java")
    _i = _rgui.find("case ID_MODE_BLINK:")
    _seg = _strip_java_comments(_rgui[_i:_i + 900]) if _i >= 0 else ""
    if "blinkInField.setText" not in _seg:
        print("      GuiRingConsole: 切闪烁方式没同步输入框"
              "（点「应用」会被旧值改回普通闪烁）")
        return 1
    # ---- v4.4：灯圈的着色选型 / 版本号 ----
    ring_block = _src("com/jiezhimod/block/BlockLightRing.java")
    if "getLightValue" not in ring_block:
        print("      BlockLightRing: 没有 getLightValue（亮着不会发光）")
        return 1
    # ★ 静默期：一组灯带同时亮/灭时，每次 setBlockState 会把 6 个邻居的
    #   neighborChanged 全叫一遍 → 又各做一次 O(N) 洪水填充。N=60 一闪就是几万次分配
    #   （实机表现"灯圈一闪，画面顿一下"）。翻 POWERED 不改成员关系，必须挡掉。
    ring_tile = _src("com/jiezhimod/block/TileLightRing.java")
    if "beginQuiet" not in ring_tile:
        print("      TileLightRing: 翻 POWERED 没有进静默期（一组灯带闪烁时会狂重建组）")
        return 1
    if "RingGroupManager.quiet()" not in ring_block:
        print("      BlockLightRing.neighborChanged: 没判静默期（自己翻状态会触发整组重建）")
        return 1
    if "notifyBlockUpdate(this.pos" in ring_tile:
        print("      TileLightRing: 又用 notifyBlockUpdate 同步状态了 —— 它只发 SPacketBlockChange，"
              "不带方块实体 NBT，快照该走 MessageRingState")
        return 1
    client = _src("com/jiezhimod/proxy/ClientProxy.java")
    ring_colors = _src("com/jiezhimod/client/ring/RingColorHandlers.java")
    if "registerBlockColorHandler" not in ring_colors or "TileLightRing.tint" not in ring_colors:
        print("      RingColorHandlers: 灯圈没有注册 IBlockColor tint（颜色永远是白的）")
        return 1
    if "ColorHandlerEvent" not in ring_colors:
        print("      RingColorHandlers: 没有挂在 ColorHandlerEvent 上")
        return 1
    if "RingColorHandlers" not in client:
        print("      ClientProxy: 没有把 RingColorHandlers 注册到事件总线（颜色处理器不会触发）")
        return 1
    # ★ v4.4 的真实崩溃：在 ModelRegistryEvent 里取 Minecraft.getBlockColors() 是 null
    #   （原版 Minecraft 构造顺序：init() 在前，blockColors 赋值在后）。这里直接把
    #   onModelRegistry 的方法体抠出来查，杜绝"又有人搬回去"。
    _i = client.find("void onModelRegistry(")
    if _i < 0:
        print("      ClientProxy: 找不到 onModelRegistry")
        return 1
    _j = client.find("{", _i)
    _depth = 0
    _k = _j
    while _k < len(client):
        if client[_k] == "{":
            _depth += 1
        elif client[_k] == "}":
            _depth -= 1
            if _depth == 0:
                break
        _k += 1
    _body = _strip_java_comments(client[_j:_k])
    for _bad in ("getBlockColors", "getItemColors", "registerBlockColorHandler",
                 "registerItemColorHandler"):
        if _bad in _body:
            print("      ClientProxy.onModelRegistry 里出现了 %s：那个阶段颜色表还是 null，"
                  "一进游戏就 NPE（v4.4 崩过一次）—— 必须用 ColorHandlerEvent" % _bad)
            return 1
    # 【为什么禁 GLSL】OptiFine/Iris 会整体接管渲染，自写 shader 一装光影就失效
    ring_pkg = os.path.join(java_src_probe, "com", "jiezhimod", "ring")
    for _f in os.listdir(ring_pkg):
        _t = _strip_java_comments(
            io.open(os.path.join(ring_pkg, _f), encoding="utf-8").read())
        for _bad in ("glCreateProgram", "GL20.glUseProgram", "OpenGlHelper.loadProgram"):
            if _bad in _t:
                print("      灯圈包里出现了自写 GLSL（%s in %s）：光影下会失效，走 tint 管线" % (_bad, _f))
                return 1
    keypad = _src("com/jiezhimod/block/BlockKeypad.java")
    if "1.0D, 1.0D" not in keypad:
        print("      BlockKeypad: 密码盘没有抬高到整格（v4.4 要求）")
        return 1
    # 版本号同步哨兵：**以 build.gradle 为唯一真源**，其余四处必须一致。
    # （旧版这里硬编码 "4.4"，每次升版本都误报，等于没有哨兵）
    _gradle_txt = io.open(os.path.join(ROOT, "build.gradle"), encoding="utf-8").read()
    _vm = re.search(r"version\s*=\s*'([^']+)'", _gradle_txt)
    if not _vm:
        print("      build.gradle 里找不到 version = '...'，版本哨兵失效")
        return 1
    _ver = _vm.group(1)
    for _vfile, _needle in (
            ("src/main/java/com/jiezhimod/JiezhiMod.java", 'VERSION = "%s"' % _ver),
            ("tools/proguard.pro", "jiezhi-1.12.2-dfcmaptools-%s.jar" % _ver),
            ("tools/seal_inject.py", "jiezhi-1.12.2-dfcmaptools-%s.jar" % _ver)):
        if _needle not in io.open(os.path.join(ROOT, _vfile), encoding="utf-8").read():
            print("      版本号没有同步到 %s：%s 缺 %s" % (_ver, _vfile, _needle))
            return 1
    _mcmod = json.load(io.open(os.path.join(ROOT, "src", "main", "resources", "mcmod.info"),
                              encoding="utf-8"))
    if _mcmod[0]["version"] != _ver:
        print("      mcmod.info 版本号不是 %s（是 %s）" % (_ver, _mcmod[0]["version"]))
        return 1
    # 灯圈必须**整格**（实机反馈：细框拼不出"一格格搭出来的灯圈"，要像红石灯）
    _m = json.load(io.open(os.path.join(res_root, "models", "block", "light_ring.json"),
                          encoding="utf-8"))
    _els = _m.get("elements", [])
    if len(_els) != 1 or _els[0].get("from") != [0, 0, 0] or _els[0].get("to") != [16, 16, 16]:
        print("      灯圈模型不是整格立方体（v4.4 已改成红石灯式整块，别改回细框）")
        return 1
    for _f, _face in _els[0].get("faces", {}).items():
        if _face.get("tintindex") != 0:
            print("      灯圈模型 %s 面缺 tintindex=0（那一面不会变色）" % _f)
            return 1
    # 状态必须自己发包：notifyBlockUpdate 只带方块状态，方块实体包运行期没人发
    net = _src("com/jiezhimod/network/ModNetwork.java")
    if "MessageRingState" not in net or "Side.CLIENT" not in net:
        print("      ModNetwork: 没有注册灯圈状态包 MessageRingState（客户端收不到颜色）")
        return 1
    grp = _src("com/jiezhimod/ring/RingGroupManager.java")
    if "RingNet.broadcast" not in grp:
        print("      RingGroupManager: 改了组状态却没广播（画面不会变色）")
        return 1
    print("      灯圈着色选型 / 整格模型 / 状态广播 / 密码盘抬高 / 版本号五处同步"
          " / 三向光源（LightType 分档光锥 + 近距截断）：源码哨兵全部命中")

    # ---- 方块实体注册自检 -------------------------------------------------
    # TileEntity.writeToNBT 内部会做 REGISTRY.getNameForObject(this.getClass())，
    # 查不到就抛 "xxx is missing a mapping! This is a bug!"。
    # 这个坑非常隐蔽：编译期完全没提示，只在**区块保存**时才炸；
    # 而且 getUpdateTag 走的是同一条路 —— 客户端的 TE 永远同步不到状态，
    # 表现成"显示器一片黑、右键也没反应"。因此这里做静态比对兜底。
    #
    # 【必须顺着继承链看】TileEntity.getKey(Class) 是按**具体类**查表的，
    # 不会因为父类注册过就认得子类。早期这一检查只匹配 "class X extends TileEntity"，
    # 于是 TileCustomScreen（extends TileDfcScreen）这种子类被整条漏掉 ——
    # 编译能过、能跑、能显示，只在区块重新加载时把方块实体连同 8 条数据一起弄丢。
    java_src = os.path.join(ROOT, "src", "main", "java")
    parent_of = {}
    abstract_classes = set()
    for dirpath, _dirs, files in os.walk(java_src):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            text = io.open(os.path.join(dirpath, fn), encoding="utf-8").read()
            cls = fn[:-5]
            m = re.search(r"(?:public\s+|final\s+|abstract\s+)*class\s+%s\b[^{]*?\bextends\s+([\w.]+)"
                          % re.escape(cls), text)
            parent_of[cls] = m.group(1).split(".")[-1] if m else None
            if re.search(r"\babstract\s+class\s+%s\b" % re.escape(cls), text):
                abstract_classes.add(cls)

    def _is_tile_entity(cls):
        """顺着本模组源码里的继承链，看它最终是不是 TileEntity 的子类"""
        seen = set()
        cur = cls
        while cur and cur not in seen:
            seen.add(cur)
            sup = parent_of.get(cur)
            if sup is None:
                return False
            if sup == "TileEntity":
                return True
            cur = sup
        return False

    te_classes = set(c for c in parent_of
                     if c not in abstract_classes and _is_tile_entity(c))
    main_src = io.open(os.path.join(java_src, "com", "jiezhimod", "JiezhiMod.java"),
                       encoding="utf-8").read()
    registered = set(re.findall(r"registerTileEntity\(\s*(?:[\w.]+\.)?(\w+)\.class", main_src))
    unregistered = sorted(te_classes - registered)
    if unregistered:
        print("      UNREGISTERED TileEntity classes (chunk save will drop the TE): %s"
              % unregistered)
        return 1
    print("      all %d TileEntity classes registered: %s"
          % (len(te_classes), sorted(te_classes)))

    # ---- 方块实体创建入口哨兵（v4.4，实机大坑） -------------------------
    # 1.12.2 实际调用的是 Block.createTileEntity(World, IBlockState)；
    # createNewTileEntity 只是 BlockContainer 那个默认实现内部才会调的。
    # 只要**某个祖先覆写了 createTileEntity**，子类只覆写 createNewTileEntity 就永远不生效：
    # 方块实体是祖先那个类，玩家看到"画面是新页面、面板却是旧面板、新东西放不进去"。
    # 这次就是这么丢掉整块激光状态显示器的（8 行面板 + 链接器放不进 + 读数永远 0/4）。
    block_src_dir = os.path.join(java_src, "com", "jiezhimod", "block")

    def _block_override(cls, needle):
        p = os.path.join(block_src_dir, cls + ".java")
        if not os.path.exists(p):
            return False
        return needle in io.open(p, encoding="utf-8").read()

    for fn in sorted(os.listdir(block_src_dir)):
        if not fn.endswith(".java"):
            continue
        text = io.open(os.path.join(block_src_dir, fn), encoding="utf-8").read()
        if "createNewTileEntity(" not in text or "createTileEntity(" in text:
            continue
        # 往上看祖先：有人覆写了 createTileEntity 才算踩坑
        ancestor = parent_of.get(fn[:-5])
        while ancestor and ancestor not in ("Block", "BlockContainer", "TileEntity"):
            if _block_override(ancestor, "createTileEntity("):
                print("      %s 只覆写了 createNewTileEntity，但它继承的 %s 覆写了 createTileEntity"
                      " —— 1.12.2 走后者，方块实体不会是它想的那个类" % (fn, ancestor))
                return 1
            ancestor = parent_of.get(ancestor)
    print("      方块实体创建入口：没有「只写 createNewTileEntity 却被祖先截胡」的方块")

    # 旧存档自愈：老存档里这些屏装的是父类的方块实体（上面那个坑留下的，NBT 里 id 还是
    # 父类），必须在 onLoad/update 里就地换掉，否则老存档永远打不开自己的面板。
    dfc_tile = io.open(os.path.join(block_src_dir, "TileDfcScreen.java"),
                       encoding="utf-8").read()
    for _need in ("public boolean healScreenTile(", "private static TileEntity desiredTileFor(",
                  "new TileLaserScreen()", "new TileCustomScreen()", "new TileLabelScreen()",
                  "public void onLoad()"):
        if _need not in dfc_tile:
            print("      TileDfcScreen 缺少旧存档自愈（%s）" % _need)
            return 1
    print("      TileDfcScreen：旧存档按方块类型自动迁移到正确的方块实体类")

    # ---- 屏幕纹理创建哨兵 -------------------------------------------------
    # 曾经踩过：ScreenCanvas.texture() 只 `return this.texture`，而全工程没人调用
    # setTexture() → 纹理恒为 null → 屏幕一片黑，且**没有任何报错**。
    # 这种静默失败在游戏里只能靠肉眼发现，所以这里盯一句代码存在性兜底。
    canvas_path = os.path.join(java_src, "com", "jiezhimod", "client", "dfc", "ScreenCanvas.java")
    canvas_src = io.open(canvas_path, encoding="utf-8").read()
    if "new DynamicTexture(" not in canvas_src:
        print("      ScreenCanvas never creates a DynamicTexture -> display would stay black")
        return 1
    print("      ScreenCanvas creates its own DynamicTexture")

    # ---- 多屏性能哨兵（v4.1） ---------------------------------------------
    # 用户反馈"屏幕一多就卡"。当时的三处硬开销已经改掉了，这里盯住别被改回去：
    #   1. 每帧 getRGB 抄一份 768x576（1.7MB）→ 改成直通 DataBufferInt，零拷贝；
    #   2. 几块屏在同一帧一起重绘 → 每帧限两块，多的推到下一帧；
    #   3. 帧边界靠 RenderWorldLastEvent 重置 → 删了这个钩子预算永不重置，
    #      表现是"屏幕好几秒才动一下"（比卡更难受，且完全没有报错）。
    for token, why in (
            ("DataBufferInt", "像素零拷贝退化成每帧 getRGB（每帧白抄 1.7MB）"),
            ("PAINT_BUDGET", "逐帧重绘预算被删（多块屏会在同一帧一起重绘）"),
            ("beginFrame", "帧边界入口被删（预算永不重置）"),
    ):
        if token not in canvas_src:
            print("      ScreenCanvas lost '%s' -> %s" % (token, why))
            return 1
    client_proxy = io.open(os.path.join(java_src, "com", "jiezhimod", "proxy",
                                        "ClientProxy.java"), encoding="utf-8").read()
    if "ScreenFrameHook" not in client_proxy:
        print("      ClientProxy does not register ScreenFrameHook -> paint budget never resets")
        return 1
    print("      ScreenCanvas: zero-copy pixels + per-frame paint budget + frame hook")

    # ---- 中栏表盘"一块盘只归一个量"哨兵 -------------------------------------
    # 需求原话："把潜在增益和崩溃程度分开，不要放一起"。
    # 早期版本在同一块表盘上叠了两个**互不相干**的量：刻度环的填充 = 堆芯崩溃程度，
    # 指针 + 右上角金色读数 = 潜在增益 —— 同一根针指着一个数、同一圈环填着另一个数。
    # 这里只盯 drawDial 的**方法体**：既不许再出现潜在增益的字样（防止悄悄搬回来），
    # 又必须仍然画着稳定度读数与崩溃百分比（防止"顺手删干净"把功能也带走）。
    dial_at = canvas_src.find("private void drawDial(")
    if dial_at < 0:
        print("      ScreenCanvas.drawDial is gone -> 中栏表盘整块没了")
        return 1
    dial_end = canvas_src.find("\n    }", dial_at)
    dial_src = canvas_src[dial_at:dial_end]
    leaked = [k for k in ("gainFactor", "potentialGain", "gainRatio", "NEEDLE") if k in dial_src]
    if leaked:
        print("      drawDial 又把潜在增益画回崩溃盘上了: %s" % leaked)
        return 1
    for need in ("DfcFormat.stabilization(", "DfcFormat.percent(d.collapsing)", "collapseLevel"):
        if need not in dial_src:
            print("      drawDial 少了必需的读数: %s" % need)
            return 1
    print("      drawDial 只读 稳定度 + 崩溃程度 (潜在增益在别处)")

    # ---- 熔毁报错页 / 重载重启 哨兵 -----------------------------------------
    # 需求："在每次存档重新加载时重启所有屏幕，在进入熔毁阶段时，显示全屏报错301，
    # 与核心失去连接，等待4秒后，重启屏幕"。三个环节各钉一句代码存在性：
    #   ① 触发必须是**坍缩度从 0 跳到 >0**（不是"大于某阈值"——核心炸了之后那个条件一直成立，
    #      屏幕会 4 秒一次没完没了地重启）；
    #   ② 4 秒到点必须**重启屏幕**（把开机时间轴重新按到现在、再走一遍自检），
    #      而不是让报错页消失、主界面凭空跳出来；
    #   ③ **重新加载存档**也要重启：客户端方块实体是随区块重建的，判据是 clientBootMs 还是 0。
    te_path = os.path.join(java_src, "com", "jiezhimod", "block", "TileDfcScreen.java")
    te_src = io.open(te_path, encoding="utf-8").read()
    if "drawFault" not in canvas_src or "faultElapsedMs" not in canvas_src:
        print("      ScreenCanvas 里没有熔毁报错页（drawFault / faultElapsedMs）")
        return 1
    for need, why in (("clientFaultElapsedMs", "报错页状态机"),
                      ("clientPrevCollapsing", "熔毁上升沿判据"),
                      ("clientBootMs <= 0L", "重新加载存档时重启")):
        if need not in te_src:
            print("      TileDfcScreen 少了 %s（%s）" % (need, why))
            return 1
    fault_at = te_src.find("public long clientFaultElapsedMs()")
    fault_body = te_src[fault_at:te_src.find("\n    }", fault_at)]
    if "clientBootMs = System.currentTimeMillis()" not in fault_body:
        print("      clientFaultElapsedMs 没有重启屏幕（应把 clientBootMs 重新按到现在）")
        return 1
    print("      熔毁报错页 + 重载重启 三处代码都在")

    # ---- 自定义屏"遥控器放进槽位点添加"哨兵 ---------------------------------
    # 需求原话："右键无法添加，改为把遥控器放入GUI的一个空，点击添加，添加记录的所有信息"。
    # 这条链路有五处最容易在后续改动里被悄悄破坏，逐条钉住。
    gui_path = os.path.join(java_src, "com", "jiezhimod", "client", "gui", "GuiCustomScreen.java")
    gui_src = io.open(gui_path, encoding="utf-8").read()

    # ① 必须是 GuiContainer。物品槽靠的是原版拖放机制，
    #    用 GuiScreen 自己画个"长得像格子"的方块，玩家是拖不进去的。
    if "extends GuiContainer" not in gui_src:
        print("      GuiCustomScreen 不再是 GuiContainer（遥控器槽拖不进去了）")
        return 1

    # ② 槽位坐标**只能有一份**。GUI 画格子、Container 放 Slot，
    #    两边各抄一遍数字，改一次版面就会错位一格 —— 玩家点到的和看到的不一样。
    for need in ("ContainerCustomScreen.PANEL_W", "ContainerCustomScreen.REMOTE_SLOT_X",
                 "ContainerCustomScreen.REMOTE_SLOT_Y", "ContainerCustomScreen.INV_X",
                 "ContainerCustomScreen.INV_Y", "ContainerCustomScreen.HOTBAR_Y"):
        if need not in gui_src:
            print("      GuiCustomScreen 没有引用 %s（槽位坐标抄成两份必然错位）" % need)
            return 1

    # ③ 1.12.2 的背景层是**绝对坐标**、前景层是**被 translate 过**的。两层坐标系不同，
    #    混着画最容易出"文字偏半屏"。约定：全部画背景层，前景层留空。
    fg_at = gui_src.find("protected void drawGuiContainerForegroundLayer(")
    if fg_at < 0:
        print("      GuiCustomScreen 没有实现 drawGuiContainerForegroundLayer")
        return 1
    fg_body = gui_src[fg_at:gui_src.find("\n    }", fg_at)]
    if "drawString" in fg_body or "GuiDraw." in fg_body:
        print("      foreground 层里又在画画了 —— 那里坐标被 translate 过，会和背景层错开")
        return 1

    # ④ 遥控器必须由**服务端自己**从玩家的 openContainer 里取，
    #    而不是信客户端包里传过来的东西（这套网络通道一贯"只发操作、不发数据"）。
    msg_path = os.path.join(java_src, "com", "jiezhimod", "network", "msg",
                            "MessageCustomScreenAction.java")
    msg_src = io.open(msg_path, encoding="utf-8").read()
    if "ACT_IMPORT = 3" not in msg_src or "player.openContainer" not in msg_src:
        print("      ACT_IMPORT 没有从服务端的 openContainer 取遥控器（等于信任了客户端）")
        return 1

    # ⑤ 两个入口一套实现：屋里蹲下右键本屏、GUI 里点「添加」，
    #    都必须落到 TileCustomScreen.importDevices。各写各的迟早给出不一样的结果。
    tile_src = io.open(os.path.join(java_src, "com", "jiezhimod", "block", "TileCustomScreen.java"),
                       encoding="utf-8").read()
    if "public int[] importDevices(" not in tile_src:
        print("      TileCustomScreen 没有 importDevices（导入逻辑没有单一实现）")
        return 1
    remote_src = io.open(os.path.join(java_src, "com", "jiezhimod", "item", "ItemDfcRemote.java"),
                         encoding="utf-8").read()
    if "screen.importDevices(" not in remote_src:
        print("      ItemDfcRemote 没走 importDevices —— 两条入口的语义会分叉")
        return 1
    print("      自定义屏：遥控器槽位 + 添加按钮（两条入口共用同一份导入实现）")

    # ⑥ v4.4 实机反馈："激光遥控器（激光链接器）无法放入"。
    #    自定义屏的槽位只收 DFC 遥控器是对的，但**被拒时一声不响**，玩家只会以为坏了。
    #    两条要求：① 容器里点/Shift 点击被拒要给一句解释；
    #             ② 手持激光链接器时底部提示要直接说"该去哪块屏"。
    container_src = io.open(os.path.join(java_src, "com", "jiezhimod", "block",
                                         "ContainerCustomScreen.java"), encoding="utf-8").read()
    if "public ItemStack slotClick(" not in container_src or "warnWrongItem" not in container_src:
        print("      ContainerCustomScreen: 槽位拒收时没有任何提示（玩家会当成 bug）")
        return 1
    if "custom.hint.laser" not in gui_src:
        print("      GuiCustomScreen: 手持激光链接器时没有换成「该去哪块屏」的提示")
        return 1

    # ⑦ 激光屏继承自定义屏，任何路径把它路由成 GUI_CUSTOM_SCREEN 都会露出
    #    8 行 + 只收遥控器的面板（激光链接器怎么点都进不去）。两份 Proxy 都要先判激光，
    #    而且判据必须一致 —— 两边容器不一致就是槽位错位 / 物品消失。
    for _pf in ("CommonProxy.java", "ClientProxy.java"):
        _p = io.open(os.path.join(java_src, "com", "jiezhimod", "proxy", _pf),
                     encoding="utf-8").read()
        _i = _p.find("id == GUI_CUSTOM_SCREEN")
        if _i < 0:
            print("      %s: 找不到 GUI_CUSTOM_SCREEN 分支" % _pf)
            return 1
        _seg = _strip_java_comments(_p[_i:_i + 900])
        if "TileLaserScreen" not in _seg:
            print("      %s: GUI_CUSTOM_SCREEN 分支没有先判 TileLaserScreen"
                  "（激光屏会露出 8 行面板，链接器放不进去）" % _pf)
            return 1

    # ---- 物品 / 方块文案：一律走语言文件，且中文必须齐全 -----------------------
    # 需求原话："所有物品，方块，物品描述都为中文"。
    # 硬编码在 Java 里就没法翻译，也没法被这里查；所以两条线一起钉：
    #   ① 代码里出现的 I18n key 必须在 zh_cn / en_us 两份语言文件里都存在；
    #   ② addInformation 里不许再出现拼接的字面串（那就是硬编码文案）。
    def read_lang(name):
        p = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod", "lang", name)
        d = {}
        for line in io.open(p, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            d[k.strip()] = v
        return d

    zh = read_lang("zh_cn.lang")
    en = read_lang("en_us.lang")

    used_keys = set()
    hardcoded = []
    for root, dirs, files in os.walk(java_src):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            p = os.path.join(root, fn)
            src = io.open(p, encoding="utf-8").read()
            for m in re.finditer(r'I18n\.format\(\s*"([^"]+)"', src):
                used_keys.add(m.group(1))
            for i, line in enumerate(src.splitlines(), 1):
                s = line.strip()
                # tooltip.add(TextFormatting.XXX + "字面串") —— 拼接了字面串就是硬编码
                if s.startswith("tooltip.add(") and ' + "' in s:
                    hardcoded.append("%s:%d" % (fn, i))

    if hardcoded:
        print("      addInformation 里还有硬编码文案（应改走 I18n）: %s" % hardcoded[:6])
        return 1

    # 有些 key 是拼出来的（"gui.jiezhimod.console.act." + i），正则只能取到前缀。
    # 前缀不能直接比对，但可以要求"以它为前缀的键在两种语言里都存在至少一条"。
    prefixes = sorted(k for k in used_keys if k.endswith("."))
    full = sorted(k for k in used_keys if not k.endswith("."))
    missing = [k for k in full if k not in zh or k not in en]
    for p in prefixes:
        if (not any(k.startswith(p) for k in zh)) or (not any(k.startswith(p) for k in en)):
            missing.append(p + "*")
    if missing:
        print("      这些 I18n key 在语言文件里缺失: %s" % missing[:8])
        return 1

    tips = sorted(k for k in zh if k.startswith("tooltip.jiezhimod."))
    if len(tips) < 30:
        print("      tooltip 语言条目只有 %d 条，像是被删过" % len(tips))
        return 1
    orphan = [k for k in tips if k not in used_keys]
    if orphan:
        print("      语言文件里有代码没用到的 tooltip 键: %s" % orphan[:8])
        return 1
    # 中文条目里必须真的有汉字，不能是英文占位
    non_cjk = [k for k in tips if not re.search(r"[\u4e00-\u9fff]", zh[k])]
    if non_cjk:
        print("      这些 tooltip 的中文条目里没有汉字: %s" % non_cjk[:8])
        return 1
    print("      物品/方块文案：%d 条 tooltip 全走语言文件，中英两份齐全、无硬编码"
          % len(tips))

    # ---- 每个 ItemBlock 都必须绑定物品模型 ---------------------------------
    # 方块模型走 blockstates（照常显示），**物品模型走 ClientProxy.onModelRegistry**。
    # 漏一步的后果是：tooltip、名字、放下去的方块全都正常，只有物品栏里那一格
    # 变成紫黑格（缺模型）。v3.9.6 的 dfc_graph 就是这么漏的 —— 实机截图里
    # 创造页第一格是紫黑方块，玩家叫它"乱码的物品"。
    # 编译期没有任何提示，只能靠这张清单钉住。
    mod_blocks = io.open(os.path.join(java_src, "com", "jiezhimod", "block", "ModBlocks.java"),
                         encoding="utf-8").read()
    client_proxy = io.open(os.path.join(java_src, "com", "jiezhimod", "proxy", "ClientProxy.java"),
                           encoding="utf-8").read()
    item_blocks = re.findall(
        r"public\s+static\s+final\s+ItemBlock\s+ITEM_([A-Z0-9_]+)\s*=\s*new\s+ItemBlock\(([A-Z0-9_]+)\)",
        mod_blocks)
    if not item_blocks:
        print("      ModBlocks 里一个 ItemBlock 都没解析到（正则失效？）")
        return 1
    unbound = []
    for _item, block in item_blocks:
        # 认两种写法：Item.getItemFromBlock(ModBlocks.XXX) 或 bindItemModel(ModBlocks.ITEM_XXX
        if ("getItemFromBlock(ModBlocks.%s)" % block) in client_proxy:
            continue
        if ("ModBlocks.ITEM_%s" % _item) in client_proxy:
            continue
        unbound.append("ITEM_%s (block %s)" % (_item, block))
    if unbound:
        print("      这些 ItemBlock 没在 ClientProxy.onModelRegistry 里绑定模型"
              "（物品栏里会是紫黑格）: %s" % unbound)
        return 1
    print("      %d 个 ItemBlock 都绑定了物品模型" % len(item_blocks))

    # ---- 密码盘顶面 quad 的绕序 -------------------------------------------
    # 【已经栽了三次的坑】顶面法线必须朝 +Y；写成 A→B→C→D（p0p0→p1p0→p1p1→p0p1）
    # 叉积出来是 **-Y**，从上方看就是背面，被 GL_CULL_FACE 整块剔掉 ——
    # 表现是"底座模型在、面板完全没有"，而且日志里一句错都没有。
    # 这里把 drawPanel / drawFallbackPanel 里的顶点顺序钉成 A→D→C→B。
    rk = io.open(os.path.join(java_src, "com", "jiezhimod", "client", "keypad", "RenderKeypad.java"),
                 encoding="utf-8").read()

    def _winding(method_body):
        return re.findall(r"buf\.pos\(\s*(p[01])\s*,\s*PANEL_Y\s*,\s*(p[01])\s*\)", method_body)

    def _method(name):
        m = re.search(r"private\s+void\s+%s\s*\([^)]*\)\s*\{" % re.escape(name), rk)
        if not m:
            return ""
        i = m.end()
        depth = 1
        while i < len(rk) and depth:
            if rk[i] == "{":
                depth += 1
            elif rk[i] == "}":
                depth -= 1
            i += 1
        return rk[m.end():i]

    expected = [("p0", "p0"), ("p0", "p1"), ("p1", "p1"), ("p1", "p0")]
    for name in ("drawPanel", "drawFallbackPanel"):
        body = _method(name)
        if not body:
            print("      RenderKeypad 里找不到 %s" % name)
            return 1
        got = _winding(body)
        if got != expected:
            print("      %s 的顶面绕序反了（法线朝 -Y 会被剔除 = 面板看不见）：%s" % (name, got))
            return 1
    if "disableCull()" not in _method("drawPanel"):
        print("      drawPanel 没有关掉背面剔除（绕序写错时会毫无兜底）")
        return 1
    print("      密码盘顶面：绕序 A->D->C->B（法线 +Y）+ 关闭背面剔除")

    # ---- 混淆后覆写方法必须还在 -------------------------------------------
    # 【为什么必须查这个】ProGuard 的 libraryjars 里解析不到 net.minecraft.*，
    # 于是它不知道 `RenderKeypad.render(...)` 是在覆写 `TileEntitySpecialRenderer.render(...)`，
    # 会把它改名成 `a(...)` 再加一个桥接方法。基类的 render 一点没变，
    # MC 调到的就是我们那个方法体 —— **空的**，而且不报错、不写日志。
    # 同理 `GuiPasswordConsole` 的 initGui / drawGuiContainerBackgroundLayer 被改名后，
    # 界面一片空白（"右键有鼠标，但什么都没画"）。
    #
    # 这个坑吃掉了 v3.9 / v3.9.2 / v3.9.6 三个版本的"密码盘不可见"，
    # 修法是 tools/proguard.pro 里整包保留 com.jiezhimod.client.**。
    # 这里再从**产物**反查一遍：类名保持原名（说明 -keep 生效）+ 方法名还在常量池里。
    # 方法名在**产品 jar 里是 SRG 名**（initGui -> func_73866_w_ 等），
    # 但 TESR 的 render 在 1.12.2 里就叫 render，所以两张表分别写。
    kept_overrides = [
        ("com/jiezhimod/client/keypad/RenderKeypad.class", ["render"]),
        ("com/jiezhimod/client/keypad/GuiPasswordConsole.class",
         ["func_73866_w_", "func_146976_a", "func_146284_a"]),
        ("com/jiezhimod/client/dfc/RenderDfcScreen.class", ["render"]),
        ("com/jiezhimod/client/gui/GuiCustomScreen.class",
         ["func_73866_w_", "func_146976_a"]),
        ("com/jiezhimod/client/chat/JiezhiChatGui.class", ["func_146230_a"]),
        # 高级红石中继器的设置界面（v4.0）：initGui / drawGuiContainerBackgroundLayer /
        # actionPerformed 一旦被改名，右键就是"什么都没发生"
        ("com/jiezhimod/client/gui/GuiAdvancedRepeater.class",
         ["func_73866_w_", "func_146976_a", "func_146284_a"]),
        # 方块实体：update(ITickable) / readFromNBT / writeToNBT 是红石计时的全部入口。
        # 这三个被改名的话，中继器会永久停在放下时的那一刻 —— 不报错、不闪、就是不动。
        ("com/jiezhimod/block/TileAdvancedRepeater.class",
         ["func_73660_a", "func_145839_a", "func_189515_b",
          "func_189517_E_", "func_189518_D_"]),
        # 调试界面（v4.0 起有三种模式：刷新率 / 标注七格 / 密码盘）。
        # initGui 与 actionPerformed 一旦被改名，蹲下右键就是"开了个空面板"，
        # 按钮全都不响应 —— 这个坑在 3.9.2~3.9.6 已经吃过一次（控制台界面空白）。
        ("com/jiezhimod/client/gui/GuiDebug.class",
         ["func_73866_w_", "func_146976_a", "func_146284_a"]),
        # 标注显示屏的方块实体：readFromNBT / writeToNBT 被改名 = 七格配置存不住，
        # 每次进存档都变回出厂值，且不报任何错。
        ("com/jiezhimod/block/TileLabelScreen.class",
         ["func_145839_a", "func_189515_b"]),
        # 激光状态屏的独立面板（v4.3）：三个入口任一被改名，
        # 右键就是"开了个空面板/按钮不响应"，和 3.9.2 的控制台一模一样。
        ("com/jiezhimod/client/gui/GuiLaserScreen.class",
         ["func_73866_w_", "func_146976_a", "func_146284_a"]),
        # 容器的关闭 / 转移 / 交互判定：关界面要把激光链接器还给玩家，
        # 这三个被改名就会出现"链接器卡在屏里拿不回来"。
        ("com/jiezhimod/block/ContainerLaserScreen.class",
         ["func_75145_c", "func_75134_a", "func_82846_b"]),
        # 激光链接器物品：onItemUse 被改名 = 蹲下右键记不了设备，且不报错
        ("com/jiezhimod/item/ItemLaserConnector.class",
         ["func_180614_a", "func_77659_a", "func_77624_a"]),
    ]
    with zipfile.ZipFile(FINAL_JAR) as z:
        jar_names = set(z.namelist())
        broken = []
        for cls_path, methods in kept_overrides:
            if cls_path not in jar_names:
                broken.append(cls_path + " (类名被混淆: -keep 没覆盖到这个包)")
                continue
            blob = z.read(cls_path)
            for m in methods:
                # 方法名在常量池里是 UTF-8 字面量，直接按字节找
                if m.encode("utf-8") not in blob:
                    broken.append("%s#%s (覆写方法被改名)" % (cls_path, m))
    if broken:
        print("      ProGuard 把覆写方法改名了，运行时会静默失效: %s" % broken[:4])
        return 1
    print("      %d 个类（客户端 GUI/TESR + 中继器方块实体）的覆写方法没被混淆改名" % len(kept_overrides))

    print("ALL DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
