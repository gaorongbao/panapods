"""解析 _milink.apk 所有 dex 的字符串池，定位 headset runtime 的关键实现。"""
import zipfile, struct, sys, re

APK = sys.argv[1] if len(sys.argv) > 1 else '_milink.apk'
PATTERNS = [
    r'HeadsetPropertyChangeListener',
    r'MODE_CHANGED',
    r'AncBatteryController',
    r'MiuiHeadsetAnimation',
    r'loadDefault',
    r'com\.xiaomi\.bluetooth\.[A-Za-z_.]*',
    r'android\.bluetooth\.[A-Za-z_.]*EXTRA',
]

def uleb(data, off):
    r = 0; s = 0
    while True:
        b = data[off]; off += 1
        r |= (b & 0x7f) << s
        if b < 0x80: break
        s += 7
    return r, off

def dex_strings(d):
    """返回 dex 字符串池列表"""
    str_size = struct.unpack_from('<I', d, 56)[0]
    str_off  = struct.unpack_from('<I', d, 60)[0]
    out = []
    for i in range(str_size):
        off = struct.unpack_from('<I', d, str_off + i*4)[0]
        _, p = uleb(d, off)
        e = d.index(b'\x00', p)
        out.append(d[p:e].decode('utf-8', 'replace'))
    return out

z = zipfile.ZipFile(APK)
hits = {}
for name in z.namelist():
    if not (name.startswith('classes') and name.endswith('.dex')):
        continue
    d = z.read(name)
    try:
        strings = dex_strings(d)
    except Exception as ex:
        print(f'[{name}] parse fail: {ex}')
        continue
    for s in strings:
        for pat in PATTERNS:
            if re.search(pat, s):
                hits.setdefault(pat, set()).add(s)

for pat, ss in hits.items():
    print(f'\n===== {pat} ({len(ss)}) =====')
    for s in sorted(ss)[:60]:
        print(s)
