from periph.transport.i2c_auto import I2CTransport
from periph.chips.memory._24aa02uid import EEPROM24AA02UIDFull
import time
import struct

transport = I2CTransport(0x50)                                                      # Create I2C transport, (addr=0x50) → Transport
eeprom = EEPROM24AA02UIDFull(transport)                                             # Create 24AA02UID driver, (transport) → None

# --- Read the chip's factory-programmed 32-bit serial number ---
# The UID at 0xFC-0xFF never changes and identifies the device across
# the entire 256-byte address space. Print it as both hex bytes and int.
uid = eeprom.read_uid()                                                             # Read 32-bit unique serial number, () → bytes
                                                                                    # reads 4 bytes at 0xFC-0xFF
print('Device UID: 0x' + uid.hex().upper())
print('Device UID int:', int.from_bytes(uid, 'big'))

# --- Maintain a 4-byte boot counter in user EEPROM at 0x00-0x03 ---
# Read the existing value (or zero on a fresh chip), increment, write
# back as 4 big-endian bytes. The user EEPROM is rewritable; the UID
# region above 0x80 is not, so the two stay independent of each other.
existing = eeprom.read(0x00, 4)                                                     # Sequential read, (address, length) → bytes
                                                                                    # reads 4 bytes from user EEPROM
counter = struct.unpack('>I', existing)[0]
counter += 1
eeprom.write(0x00, struct.pack('>I', counter))                                      # Arbitrary-length write, (address, data) → None
                                                                                    # writes 4 bytes; ACK-polls when done
print('Boot count: %d' % counter)

# --- Loop reading the UID only, showing it never changes ---
# The two distinct areas of the chip (immutable identification above
# 0x80, rewritable storage below 0x80) are exercised independently.
for n in range(5):
    uid = eeprom.read_uid()                                                         # Read 32-bit unique serial number, () → bytes
    print('[%d] UID: 0x%s  (counter now at user EEPROM 0x00-0x03)' % (n, uid.hex().upper()))
    time.sleep(2)
