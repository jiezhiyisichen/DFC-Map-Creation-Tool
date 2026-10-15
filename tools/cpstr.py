#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cpstr.py —— 无依赖的 class 文件常量池字符串提取器（纯标准库）。

用途：分析第三方 mod 的 class 时，快速拿到里面出现的所有字符串常量
（界面标签、NBT key、反射用的方法名、i18n key 等），不需要反编译器。

用法:
    python tools/cpstr.py <a.class> [b.class ...]
    python tools/cpstr.py path/to/classes_dir/*.class

为什么不用 javap:
    javap -c 输出的是字节码指令流，要读某个方法里的字符串得靠 grep 上下文猜；
    本项目分析 HBM/DFC 这类大型 mod 时，直接 dump 常量池更快且不会漏。
    需要"某个方法里到底 put 了哪些 key"时，再配合:
        javap -p -c X.class | sed -n '/getQueryData/,/^  [a-z]/p'
"""
import sys
import struct


def utf8_consts(path):
    """返回 class 常量池中所有 CONSTANT_Utf8 字符串；非 class 文件返回 None。"""
    with open(path, "rb") as f:
        d = f.read()
    if d[:4] != b"\xca\xfe\xba\xbe":
        return None
    i = 8
    count = struct.unpack(">H", d[i:i + 2])[0]
    i += 2
    out = []
    k = 1
    while k < count:
        tag = d[i]
        i += 1
        if tag == 1:                       # CONSTANT_Utf8
            ln = struct.unpack(">H", d[i:i + 2])[0]
            i += 2
            out.append(d[i:i + ln].decode("utf-8", "replace"))
            i += ln
        elif tag in (7, 8, 16, 19, 20):    # Class/String/MethodType/Module/Package
            i += 2
        elif tag == 15:                    # MethodHandle
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 4
        elif tag in (5, 6):                # Long/Double 占两个常量位
            i += 8
            k += 1
        else:
            break
        k += 1
    return out


def is_noise(s):
    """过滤掉类型描述符这类噪声，只留"人话"字符串。"""
    if not s:
        return True
    if s.startswith(("(", "L", "[")):
        return True
    if s.endswith(";") and "/" in s:
        return True
    return "\x00" in s


def main(argv):
    if not argv:
        print(__doc__)
        return 1
    for p in argv:
        consts = utf8_consts(p)
        print("=" * 24, p.split("/")[-1], "=" * 24)
        if consts is None:
            print("   (not a class file)")
            continue
        for s in consts:
            if not is_noise(s):
                print("   ", repr(s))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
