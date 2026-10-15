# -*- coding: utf-8 -*-
"""篡改测试工具：从源 jar 生成各种被篡改的 jar，用来验证 Seal 校验真的会拦下来。

用法：python tools/tamper.py <mode>
  mode: flip_business_class | flip_locked_class | remove_sealb | remove_seala | replace_sealb_empty
源 jar 默认取 build/libs 下的主产物（排除 -sources / -javadoc）。
"""
import glob
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def default_src():
    cands = [p for p in sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")))
             if "sources" not in os.path.basename(p) and "javadoc" not in os.path.basename(p)]
    if not cands:
        raise SystemExit("找不到源 jar，请先构建（gradlew build + proguard + seal_inject）")
    return cands[0]


SRC = default_src()
OUT_DIR = os.path.join(ROOT, "tools", "tampered")


def copy_jar(src, dst, modify=None, remove=None):
    """modify: {name: bytes} 替换条目；remove: [names] 删除条目"""
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if remove and item.filename in remove:
                continue
            data = zin.read(item.filename)
            if modify and item.filename in modify:
                data = modify[item.filename]
            zout.writestr(item, data)


def main():
    import os
    os.makedirs(OUT_DIR, exist_ok=True)
    mode = sys.argv[1]

    if mode == "flip_business_class":
        # 翻转 JiezhiMod.class 第一个字节（改业务类 → H_rest 变）
        with zipfile.ZipFile(SRC) as z:
            data = bytearray(z.read("com/jiezhimod/JiezhiMod.class"))
        data[0] ^= 0x01
        copy_jar(SRC, os.path.join(OUT_DIR, "tampered_business.jar"), modify={"com/jiezhimod/JiezhiMod.class": bytes(data)})
        print("wrote tampered_business.jar")

    elif mode == "flip_locked_class":
        # 翻转一个混淆类（可能是锁定逻辑类）的字节
        with zipfile.ZipFile(SRC) as z:
            names = [i.filename for i in z.infolist() if i.filename.endswith(".class")
                     and not i.filename.startswith("com/jiezhimod/seal/")]
        target = names[len(names) // 2]
        data = bytearray(z.read(target))
        data[5] ^= 0x01
        copy_jar(SRC, os.path.join(OUT_DIR, "tampered_class.jar"), modify={target: bytes(data)})
        print("tampered:", target)

    elif mode == "remove_sealb":
        copy_jar(SRC, os.path.join(OUT_DIR, "removed_sealb.jar"), remove=["com/jiezhimod/seal/SealB.class"])
        print("wrote removed_sealb.jar")

    elif mode == "remove_seala":
        copy_jar(SRC, os.path.join(OUT_DIR, "removed_seala.jar"), remove=["com/jiezhimod/seal/SealA.class"])
        print("wrote removed_seala.jar")

    elif mode == "replace_sealb_empty":
        # 把 SealB 换成无校验的"空实现"（同名类，verify 空方法）——模拟攻击者删除校验逻辑
        empty = b"\x00" * 200  # 无效 class，模拟删改（JVM 会拒绝加载；SealA 存在性检查先崩）
        copy_jar(SRC, os.path.join(OUT_DIR, "empty_sealb.jar"), modify={"com/jiezhimod/seal/SealB.class": empty})
        print("wrote empty_sealb.jar")


if __name__ == "__main__":
    main()
