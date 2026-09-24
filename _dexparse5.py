import zipfile, struct

def uleb(data, off):
    r=0; s=0
    while True:
        b=data[off]; off+=1
        r|=(b&0x7f)<<s
        if b<0x80: break
        s+=7
    return r, off

z=zipfile.ZipFile('_milink.apk')
d=z.read('classes2.dex')
str_off=struct.unpack_from('<I',d,60)[0]
type_off=struct.unpack_from('<I',d,68)[0]
proto_off=struct.unpack_from('<I',d,76)[0]
method_off=struct.unpack_from('<I',d,92)[0]
cls_size=struct.unpack_from('<I',d,96)[0]
cls_off=struct.unpack_from('<I',d,100)[0]

def sstr(idx):
    off=struct.unpack_from('<I',d,str_off+idx*4)[0]
    _,p=uleb(d,off)
    e=d.index(b'\x00',p)
    return d[p:e].decode('utf-8','replace')

def tstr(idx):
    return sstr(struct.unpack_from('<I',d,type_off+idx*4)[0])

target='Lcom/miui/circulate/device/service/search/a;'
for ci in range(cls_size):
    try:
        cd=cls_off+ci*32
        if tstr(struct.unpack_from('<I',d,cd)[0])!=target: continue
        cdata_off=struct.unpack_from('<I',d,cd+24)[0]
        p=cdata_off
        sf,p=uleb(d,p); inf,p=uleb(d,p); dm,p=uleb(d,p); vm,p=uleb(d,p)
        for _ in range(sf+inf):
            diff,p=uleb(d,p); acc,p=uleb(d,p)
        midx=0
        for _ in range(dm+vm):
            diff,p=uleb(d,p); midx+=diff
            acc,p=uleb(d,p); code,p=uleb(d,p)
            m=method_off+midx*8
            nm=sstr(struct.unpack_from('<I',d,m+4)[0])
            if nm=='getBluetoothDeviceBattery':
                proto_idx=struct.unpack_from('<H',d,m+2)[0]
                pr=proto_off+proto_idx*12
                shorty_idx=struct.unpack_from('<I',d,pr)[0]
                print('method:',nm,'shorty:',sstr(shorty_idx),'acc=%#x'%acc)
        break
    except Exception:
        pass
