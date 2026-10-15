# -*- coding: utf-8 -*-
"""检查 jar 内所有 class：major version 与每个方法的 StackMapTable 完整性。
JVM8 split verifier 要求：版本>=51 的类，含分支指令的方法必须有 StackMapTable，
否则 VerifyError: Expecting a stackmap frame at branch target。
用法: python check_stackmap.py <jar>
"""
import struct
import sys
import zipfile

# 真实分支指令 opcode
BRANCH_OPS = {0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2,
              0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xC6, 0xC7,
              0xAA, 0xAB, 0xC8, 0xC9}
# 非 1 字节指令的操作数长度（变长指令在检测到分支后直接返回，无需推进）
OP_LEN = {
    0x10: 2, 0x11: 3, 0x12: 2, 0x13: 3, 0x14: 3,
    0x15: 2, 0x16: 2, 0x17: 2, 0x18: 2, 0x19: 2,
    0x84: 3, 0xBC: 2, 0xC0: 3, 0xC1: 3, 0xC4: 3, 0xC5: 4,
    0xB2: 3, 0xB3: 3, 0xB4: 3, 0xB5: 3, 0xB6: 3, 0xB7: 3, 0xB8: 3,
    0xB9: 5, 0xBA: 5, 0xBB: 3, 0xBD: 3,
}


def has_real_branch(code):
    """按 opcode 逐条解析字节码，只认真实分支指令（不把操作数误判为分支）。"""
    i = 0
    n = len(code)
    while i < n:
        op = code[i]
        if op in BRANCH_OPS:
            return True
        i += OP_LEN.get(op, 1)
    return False


def parse_class(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        return None
    major = struct.unpack('>H', data[6:8])[0]
    idx = 8
    count = struct.unpack('>H', data[idx:idx + 2])[0]
    idx += 2
    cp = {}
    i = 1
    while i < count:
        tag = data[idx]
        idx += 1
        if tag == 1:
            ln = struct.unpack('>H', data[idx:idx + 2])[0]
            idx += 2
            cp[i] = data[idx:idx + ln].decode('utf-8', 'replace')
            idx += ln
        elif tag in (7, 8, 16, 19, 20):
            idx += 2
        elif tag == 15:
            idx += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            idx += 4
        elif tag in (5, 6):
            idx += 8
            i += 1
        i += 1
    idx += 6  # access, this, super
    ic = struct.unpack('>H', data[idx:idx + 2])[0]
    idx += 2
    for _ in range(ic):
        idx += 2  # 每个接口是 CONSTANT_Class index(2字节)
    fc = struct.unpack('>H', data[idx:idx + 2])[0]
    idx += 2
    for _ in range(fc):
        idx += 6
        ac = struct.unpack('>H', data[idx:idx + 2])[0]
        idx += 2
        for _ in range(ac):
            at = struct.unpack('>H', data[idx:idx + 2])[0]
            idx += 2
            al = struct.unpack('>I', data[idx:idx + 4])[0]
            idx += 4 + al
    mc = struct.unpack('>H', data[idx:idx + 2])[0]
    idx += 2
    problems = []
    for _ in range(mc):
        idx += 6  # access, name, desc
        an = struct.unpack('>H', data[idx:idx + 2])[0]
        idx += 2
        code = None
        for _ in range(an):
            ati = struct.unpack('>H', data[idx:idx + 2])[0]
            idx += 2
            al = struct.unpack('>I', data[idx:idx + 4])[0]
            idx += 4
            adata = data[idx:idx + al]
            idx += al
            if cp.get(ati) == 'Code':
                code = adata
        if code is None:
            continue
        # Code attr: max_stack(2) max_locals(2) code_length(4) code exception_table attr_count
        clen = struct.unpack('>I', code[4:8])[0]
        cbody = code[8:8 + clen]
        has_branch = has_real_branch(cbody)
        pos = 8 + clen
        etlen = struct.unpack('>H', code[pos:pos + 2])[0]
        pos += 2 + etlen * 8
        ac2 = struct.unpack('>H', code[pos:pos + 2])[0]
        pos += 2
        has_sm = False
        for _ in range(ac2):
            ati2 = struct.unpack('>H', code[pos:pos + 2])[0]
            pos += 2
            al2 = struct.unpack('>I', code[pos:pos + 4])[0]
            pos += 4
            if cp.get(ati2) == 'StackMapTable':
                has_sm = True
            pos += al2
        if has_branch and not has_sm:
            problems.append('branch-without-stackmap')
    return major, problems


def main():
    jar = sys.argv[1]
    bad = []
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if not n.endswith('.class'):
                continue
            r = parse_class(z.read(n))
            if r is None:
                bad.append((n, 'invalid-class'))
                continue
            major, probs = r
            if major > 52:
                bad.append((n, 'major=%d (>52, JVM8无法加载)' % major))
            for p in probs:
                bad.append((n, p))
    if bad:
        print('FAIL: %d 个类有问题:' % len(bad))
        for n, p in bad:
            print('  %s -> %s' % (n, p))
        sys.exit(1)
    print('OK: 所有 class major<=52，含分支的方法均有 StackMapTable')


if __name__ == '__main__':
    main()
