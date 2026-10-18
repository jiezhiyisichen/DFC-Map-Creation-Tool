# -*- coding: utf-8 -*-
"""
Seal 注入脚本（两步冻结，打破循环依赖）：

    Step 0: 用 ASM 为混淆后的所有 class 重算 StackMapTable（reobfJar 会丢掉它）
    Step 1: 从混淆 jar 计算 H_rest（排除 seal 包），生成真实 SealA（仅含 H_rest）并注入 → SealA 字节冻结
    Step 2: 计算 H_A = SHA-256(冻结后 SealA.class)，生成真实 SealB（H_rest + H_A）并注入 → 最终 jar

运行时：SealA 校验 H_rest + SealB 存在性；SealB 校验 H_rest + SealA 内容。无循环依赖。

用法（在项目根或任意目录都可）：
    python tools/seal_inject.py [输入jar] [输出jar]
默认：输入 tools/obf.jar，输出 build/libs/jiezhi-1.12.2-dfcmaptools-3.8.jar

本脚本**不写死任何机器专属路径**：
  · 项目根目录由本文件位置推导
  · JDK 依次从 JAVA_HOME → PATH 上的 javac → 常见安装目录 探测
  · 依赖 jar（Forge / ASM / netty / log4j / guava）从 ~/.gradle 缓存里按文件名搜索，
    必要时也搜 ~/.minecraft/libraries
"""
import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile

KEY = 0x5A

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "tools")
TMP = os.path.join(TOOLS, "seal_tmp")
FIXFRAMES_DIR = os.path.join(TOOLS, "fixframes")

SRC_SEALA = os.path.join(ROOT, "src", "main", "java", "com", "jiezhimod", "seal", "SealA.java")
SRC_SEALB = os.path.join(ROOT, "src", "main", "java", "com", "jiezhimod", "seal", "SealB.java")

SEAL_A_PATH = "com/jiezhimod/seal/SealA.class"
SEAL_B_PATH = "com/jiezhimod/seal/SealB.class"

HOME = os.path.expanduser("~")
GRADLE = os.path.join(HOME, ".gradle")


# --------------------------------------------------------------------------- #
# 环境探测
# --------------------------------------------------------------------------- #
def _jdk_major(bin_dir):
    """读 JDK 的 release 文件拿主版本号；读不到返回 0"""
    rel = os.path.join(os.path.dirname(bin_dir), "release")
    try:
        with open(rel, encoding="utf-8", errors="replace") as fh:
            m = re.search(r'JAVA_VERSION="(\d+)', fh.read())
            if m:
                return int(m.group(1))
    except OSError:
        pass
    return 0


def find_jdk_bin():
    """
    返回一个 **JDK 9 及以上** 的 bin 目录。

    注意：本脚本的编译步骤用到 `javac --release 8`（JDK 9 才有的选项），
    而 FixStackMap 是按当前编译器版本产出的 class，必须用**同一个 JDK** 运行 ——
    所以不能图省事取 PATH 上第一个 javac（可能是 JDK 8，编出来跑不了）。
    这里把 JAVA_HOME、PATH、常见安装目录全部收集起来，**按版本取最高**。
    """
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
                r"C:\Program Files (x86)\*\jdk*\bin",
                "/usr/lib/jvm/*/bin",
                "/opt/java/*/bin"):
        cands.extend(glob.glob(pat))

    best, best_ver = None, 0
    for b in cands:
        if not os.path.isfile(os.path.join(b, "javac" + ext)):
            continue
        if not os.path.isfile(os.path.join(b, "java" + ext)):
            continue
        ver = _jdk_major(b)
        if ver >= 9 and ver > best_ver:
            best, best_ver = b, ver
    if best:
        return best
    raise RuntimeError(
        "找不到可用的 JDK（需要 9 及以上，编译用到 javac --release 8）。\n"
        "请设置环境变量 JAVA_HOME 指向 JDK，或把 JDK 的 bin 加入 PATH。")


JDK_BIN = find_jdk_bin()
_JEXT = ".exe" if os.name == "nt" else ""
JAVAC = os.path.join(JDK_BIN, "javac" + _JEXT)
JAVA = os.path.join(JDK_BIN, "java" + _JEXT)


def _search_jar(bases, pred, what):
    """在若干候选目录下按文件名搜索 jar；找不到就给出清晰报错"""
    for base in bases:
        if not os.path.isdir(base):
            continue
        for dirpath, _dirs, files in os.walk(base):
            for f in files:
                if pred(f):
                    return os.path.join(dirpath, f)
    raise RuntimeError(
        "找不到 %s。\n已搜索：\n  %s\n"
        "提示：先执行一次 `gradlew build` 让 Gradle 把依赖下载到本地缓存。"
        % (what, "\n  ".join(bases)))


def resolve_deps():
    """返回 (asm_jar, forge_jar, netty_jar, log4j_jar, guava_jar)"""
    asm_bases = [
        os.path.join(GRADLE, "caches"),
        os.path.join(HOME, ".minecraft", "libraries"),
    ]
    asm = _search_jar(asm_bases,
                     lambda f: f.startswith("asm-debug-all") and f.endswith(".jar"),
                     "ASM (asm-debug-all)")

    forge_bases = [
        os.path.join(GRADLE, "caches", "forge_gradle", "minecraft_user_repo"),
    ]
    forge = _search_jar(forge_bases,
                        lambda f: f.endswith("-recomp.jar"),
                        "Forge 的 -recomp.jar")

    mod_bases = [
        os.path.join(GRADLE, "caches", "modules-2", "files-2.1"),
        os.path.join(GRADLE, "caches", "forge_gradle", "maven_downloader"),
        os.path.join(HOME, ".minecraft", "libraries"),
    ]
    netty = _search_jar(mod_bases,
                        lambda f: f.startswith("netty-all") and f.endswith(".jar"),
                        "netty-all")
    log4j = _search_jar(mod_bases,
                        lambda f: f.startswith("log4j-api") and f.endswith(".jar"),
                        "log4j-api")
    guava = _search_jar(mod_bases,
                        lambda f: f.startswith("guava-") and f.endswith(".jar")
                        and "sources" not in f,
                        "guava")
    return asm, forge, netty, log4j, guava


def default_input():
    return os.path.join(TOOLS, "obf.jar")


def default_output():
    """build/libs 下的主产物（排除 -sources / -javadoc）"""
    cands = [p for p in sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")))
             if "sources" not in os.path.basename(p) and "javadoc" not in os.path.basename(p)]
    if cands:
        return cands[0]
    return os.path.join(ROOT, "build", "libs", "jiezhi-1.12.2-dfcmaptools-3.8.jar")


# --------------------------------------------------------------------------- #
# 核心逻辑
# --------------------------------------------------------------------------- #
def java_bytes(values):
    lines = []
    for i in range(0, len(values), 6):
        chunk = values[i:i + 6]
        lines.append("            " + ", ".join("(byte)0x%02X" % v for v in chunk) + ",")
    return "\n".join(lines)


def patch_source(txt, field, values):
    pat = re.compile(r"(private static final byte\[\] %s = new byte\[\] \{.*?\};)" % field, re.S)
    new_block = "private static final byte[] %s = new byte[] {\n%s\n        };" % (field, java_bytes(values))
    txt2, n = pat.subn(new_block, txt, count=1)
    if n != 1:
        raise RuntimeError("patch failed for %s" % field)
    return txt2


def jar_entries(path):
    with zipfile.ZipFile(path) as z:
        return [(i.filename, z.read(i.filename)) for i in z.infolist()]


def hash_rest(entries):
    md = hashlib.sha256()
    items = []
    for name, data in entries:
        if not name.endswith(".class"):
            continue
        if name.startswith("com/jiezhimod/seal/"):
            continue
        items.append((name, data))
    items.sort(key=lambda x: x[0])
    for name, data in items:
        md.update(name.encode("utf-8"))
        md.update(b"\x00")
        md.update(data)
    return md.digest()


def hash_entry(entries, path):
    for name, data in entries:
        if name == path:
            return hashlib.sha256(data).digest()
    raise RuntimeError("entry not found: " + path)


def compile_java(src, outdir):
    os.makedirs(outdir, exist_ok=True)
    r = subprocess.run([JAVAC, "--release", "8", "-encoding", "UTF-8", "-d", outdir, src],
                       capture_output=True, text=True, encoding="gbk", errors="replace")
    if r.returncode != 0:
        raise RuntimeError("javac failed:\n" + r.stdout + r.stderr)
    print("compiled:", os.path.basename(src))


def rewrite_jar(src_jar, out_jar, replacements):
    os.makedirs(os.path.dirname(os.path.abspath(out_jar)), exist_ok=True)
    with zipfile.ZipFile(src_jar) as zin, zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename in replacements:
                data = replacements[item.filename]
            zout.writestr(item, data)
    print("wrote:", out_jar)


def fix_stackmaps(obf_jar, fixed_jar, asm_jar, forge_jar, netty_jar, log4j_jar, guava_jar):
    """Step 0: 用 ASM COMPUTE_FRAMES 为所有 class 重算 StackMapTable（reobfJar 会丢失）。"""
    fix_src = os.path.join(FIXFRAMES_DIR, "FixStackMap.java")
    fix_cls = os.path.join(FIXFRAMES_DIR, "FixStackMap.class")
    if not os.path.exists(fix_cls) or os.path.getmtime(fix_src) > os.path.getmtime(fix_cls):
        subprocess.run([JAVAC, "-encoding", "UTF-8", "-cp", asm_jar, fix_src], check=True)
    cp = os.pathsep.join([FIXFRAMES_DIR, asm_jar, forge_jar, netty_jar, log4j_jar, guava_jar, obf_jar])
    r = subprocess.run([JAVA, "-cp", cp, "FixStackMap", obf_jar, fixed_jar],
                       capture_output=True, text=True, encoding="gbk", errors="replace")
    if r.returncode != 0:
        raise RuntimeError("FixStackMap failed:\n" + r.stdout + r.stderr)
    print(r.stdout.strip())
    return fixed_jar


def main():
    os.makedirs(TMP, exist_ok=True)
    obf = sys.argv[1] if len(sys.argv) > 1 else default_input()
    out = sys.argv[2] if len(sys.argv) > 2 else default_output()
    jar1 = os.path.join(TMP, "jar1.jar")
    fixed = os.path.join(TMP, "fixed.jar")

    for p in (obf, SRC_SEALA, SRC_SEALB):
        if not os.path.exists(p):
            raise RuntimeError("找不到文件：%s" % p)

    print("JDK  :", JDK_BIN)
    print("输入 :", obf)
    print("输出 :", out)

    asm, forge, netty, log4j, guava = resolve_deps()
    print("ASM  :", asm)

    entries = jar_entries(obf)
    h_rest = hash_rest(entries)
    print("H_rest =", h_rest.hex())

    # ---- Step 0: ASM 补 StackMapTable ----
    fix_stackmaps(obf, fixed, asm, forge, netty, log4j, guava)
    entries = jar_entries(fixed)

    # ---- Step 1: SealA（仅 H_rest）----
    src_a = patch_source(open(SRC_SEALA, encoding="utf-8").read(), "P_R",
                         [b ^ KEY for b in h_rest])
    fa = os.path.join(TMP, "SealA.java")
    open(fa, "w", encoding="utf-8").write(src_a)
    compile_java(fa, TMP)
    seal_a = open(os.path.join(TMP, SEAL_A_PATH), "rb").read()

    rewrite_jar(obf, jar1, {SEAL_A_PATH: seal_a})

    # ---- Step 2: H_A + SealB（H_rest + H_A）----
    h_a = hashlib.sha256(seal_a).digest()
    print("H_SealA =", h_a.hex())

    src_b = patch_source(open(SRC_SEALB, encoding="utf-8").read(), "P_R",
                         [b ^ KEY for b in h_rest])
    src_b = patch_source(src_b, "P_A", [b ^ KEY for b in h_a])
    fb = os.path.join(TMP, "SealB.java")
    open(fb, "w", encoding="utf-8").write(src_b)
    compile_java(fb, TMP)
    seal_b = open(os.path.join(TMP, SEAL_B_PATH), "rb").read()

    rewrite_jar(jar1, out, {SEAL_B_PATH: seal_b})

    # ---- 自检 ----
    final_entries = jar_entries(out)
    if hash_rest(final_entries) != h_rest:
        raise RuntimeError("SELF-CHECK FAILED: H_rest changed after injection")
    if hash_entry(final_entries, SEAL_A_PATH) != h_a:
        raise RuntimeError("SELF-CHECK FAILED: SealA hash mismatch")
    if hash_entry(final_entries, SEAL_B_PATH) != hashlib.sha256(seal_b).digest():
        raise RuntimeError("SELF-CHECK FAILED: SealB byte mismatch")
    print("self-check OK: H_rest stable, SealA pinned by SealB, both seals present")


if __name__ == "__main__":
    main()
