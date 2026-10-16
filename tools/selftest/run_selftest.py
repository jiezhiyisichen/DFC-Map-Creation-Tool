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
    cands = [p for p in sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")))
             if "sources" not in os.path.basename(p) and "javadoc" not in os.path.basename(p)]
    if not cands:
        raise SystemExit("找不到 build/libs 下的最终 jar，请先构建")
    return cands[0]


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
        assert "delay_block" in desc, "description does not document the delay block"
        # DFC 显示器是最大的一块硬件，说明书里漏掉的话玩家根本不知道有第二块屏
        assert "dfc_screen" in desc, "description does not document the DFC monitor panel"
        assert "dfc_monitor" in desc, "description does not document the component monitor"
        assert "dfc_custom" in desc, "description does not document the custom display"
        assert "部件监视器" in desc, "description does not explain the component page"
        assert "自定义显示屏" in desc, "description does not explain the custom display page"
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
        # 红石延时器：blockstates / 模型 / 贴图漏打包不会报错，只会静默变成紫黑块
        "assets/jiezhimod/blockstates/delay_block.json",
        "assets/jiezhimod/models/block/delay_block.json",
        "assets/jiezhimod/models/block/delay_block_on.json",
        "assets/jiezhimod/models/item/delay_block.json",
        "assets/jiezhimod/textures/blocks/delay_side.png",
        "assets/jiezhimod/textures/blocks/delay_top.png",
        "assets/jiezhimod/textures/blocks/delay_side_on.png",
        "assets/jiezhimod/textures/blocks/delay_top_on.png",
    ]
    # 模型 / blockstates 之间的引用完整性：任何一处对不上，游戏里就是"透明方块"或
    # 紫黑缺模型，而且日志里常常只是一行 warning，极难排查。这里静态全部查一遍。
    res_root = os.path.join(ROOT, "src", "main", "resources", "assets", "jiezhimod")
    json_files = [
        "blockstates/delay_block.json",
        "models/block/delay_block.json",
        "models/block/delay_block_on.json",
        "models/item/delay_block.json",
        "models/item/speed_coil.json",
        "models/item/gravity_coil.json",
        "models/item/healing_coil.json",
        "models/item/dfc_screen.json",
        "models/item/dfc_remote.json",
        "models/item/dfc_monitor.json",
        "models/item/dfc_custom.json",
        "blockstates/dfc_screen.json",
        "blockstates/dfc_screen_part.json",
        "blockstates/dfc_monitor.json",
        "blockstates/dfc_custom.json",
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

    def _resolve_tex(ref):
        name = ref.split(":")[-1]
        return os.path.join(res_root, "textures", name.replace("/", os.sep) + ".png")

    # blockstates -> model（每个方块状态文件都查；漏一个就是"透明/紫黑块"）
    for bs_rel in ("blockstates/delay_block.json",
                   "blockstates/dfc_screen.json",
                   "blockstates/dfc_screen_part.json",
                   "blockstates/dfc_monitor.json",
                   "blockstates/dfc_custom.json"):
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
            assert os.path.isfile(_resolve_tex(tex)), rel + " missing texture: " + tex

    # 语言键缺失时游戏里会直接把 key 名显示出来（如 key.jiezhimod.notice），必须断言
    lang_keys = [
        "key.jiezhimod.notice",
        "key.categories.jiezhimod",
        "gui.jiezhimod.notice.title",
        "gui.jiezhimod.notice.hint",
        "gui.jiezhimod.notice.empty",
        "tile.delay_block.name",
        "tile.dfc_screen.name",
        "tile.dfc_monitor.name",
        "tile.dfc_custom.name",
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
        "gui.jiezhimod.delay.title",
        "gui.jiezhimod.delay.delay",
        "gui.jiezhimod.delay.unit",
        "gui.jiezhimod.delay.mode",
        "gui.jiezhimod.delay.unit.0",
        "gui.jiezhimod.delay.unit.1",
        "gui.jiezhimod.delay.unit.2",
        "gui.jiezhimod.delay.mode.0",
        "gui.jiezhimod.delay.mode.1",
        "gui.jiezhimod.delay.mode.2",
        "gui.jiezhimod.delay.mode.3",
        "gui.jiezhimod.delay.actual",
        "gui.jiezhimod.delay.status",
        "gui.jiezhimod.delay.on",
        "gui.jiezhimod.delay.off",
        "gui.jiezhimod.delay.io.in",
        "gui.jiezhimod.delay.io.out",
        "gui.jiezhimod.delay.io.none",
        "gui.jiezhimod.delay.hint",
        "gui.jiezhimod.delay.dir.up",
        "gui.jiezhimod.delay.dir.north",
        "gui.jiezhimod.delay.dir.west",
        "gui.jiezhimod.delay.dir.none",
        "gui.jiezhimod.delay.dir.down",
        "gui.jiezhimod.delay.dir.south",
        "gui.jiezhimod.delay.dir.east",
        "gui.jiezhimod.delay.close",
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

    print("ALL DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
