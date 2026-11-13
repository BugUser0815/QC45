"""Python 2.7/3: probe actual ELF bindings using a fake CAN object; no I/O."""
from __future__ import print_function
import ctypes
import json
import os
import struct
import sys

candidate=os.path.abspath(sys.argv[1])
assert struct.calcsize('P')==4, 'ARM EABI32 required'
assert candidate in os.environ.get('LD_PRELOAD','').split(':'), 'Candidate must be preloaded'
base=ctypes.CDLL('/home/evacom/libETransport.so.1',mode=ctypes.RTLD_GLOBAL)
shim=ctypes.CDLL(candidate)
assert shim.qc45_current_limit_version()==6
assert shim.qc45_current_limit_bindings_ok()==1
typeinfo=ctypes.addressof(ctypes.c_char.in_dll(base,'_ZTI18CanPowerElectronic'))
vtable=ctypes.addressof(ctypes.c_char.in_dll(base,'_ZTV18CanPowerElectronic'))
words=(ctypes.c_uint32*(0x150//4)).from_address(vtable)
secondary=[i for i in range(len(words)-1) if words[i]==0xfffffff8 and words[i+1]==typeinfo]
assert len(secondary)==1, 'Unexpected secondary-interface vtable'
current=shim._ZThn8_N18CanPowerElectronic26getEVSEMaximumCurrentLimitEv
power=shim._ZThn8_N18CanPowerElectronic24getEVSEMaximumPowerLimitEv
assert ctypes.cast(current,ctypes.c_void_p).value in words, 'Current getter not interposed in real vtable'
assert ctypes.cast(power,ctypes.c_void_p).value in words, 'Power getter not interposed in real vtable'
data=bytearray(256)
struct.pack_into('<I',data,0,vtable+8)
struct.pack_into('<I',data,8,vtable+4*(secondary[0]+2))
for offset,value in ((0x34,500),(0x46,125),(0x44,3731),(0x52,5000)):
    struct.pack_into('<H',data,offset,value)
for offset,value in ((0x5d,3),(0x5c,3),(0x62,2)):
    data[offset]=value
buf=(ctypes.c_ubyte*len(data)).from_buffer(data)
for f in (current,power):
    f.argtypes=[ctypes.c_void_p];f.restype=ctypes.c_int16
address=ctypes.addressof(buf)+8
assert current(address)==11
assert power(address)==5000
struct.pack_into('<H',data,0x32,6000)
data[0x5e]=3
assert current(address)==16
assert power(address)==6000, 'Live limit must supersede the discovery-only schedule'
struct.pack_into('<H',data,0x32,5000)
data[0x5e]=4
assert current(address)==0 and power(address)==0, 'Reject the 50-kW shutdown default'
struct.pack_into('<H',data,0x32,0)
assert current(address)==11 and power(address)==5000
print(json.dumps({'actual_ELF_bindings':True,'actual_CanPowerElectronic_RTTI':True,
 'actual_vtable_interposition':True,'fake_object_current_limit_A':current(address),
 'fake_object_power_limit_W':power(address),'dynamic_limit_and_shutdown_checks':True,
 'hardware_IO':False},sort_keys=True))
