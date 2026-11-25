"""Linux hardware test for the MX25L12845E NOR flash — runs against real hardware.

Checks the ids, status and SFDP, erase / program / erase-aware write, block erases,
the BlockDevice methods, block protection and deep power-down. All erases and writes
stay inside the last 64 KiB of the chip, which is left blank.
"""

import os
from periph.connection.spi_linux import SPIConnection
from periph.chips.memory.mx25l12845e import MX25L12845EFull
from periph.storage.block_device import OutOfRange, NotErased, WriteProtected

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def raises(exc, fn):
    try:
        fn()
    except exc:
        return True
    except Exception:
        return False
    return False


SPI_BUS = int(os.environ.get('SPI_BUS', '0'))
SPI_DEVICE = int(os.environ.get('SPI_DEVICE', '0'))

connection = SPIConnection(SPI_BUS, SPI_DEVICE, mode=0, max_speed_hz=8_000_000)

flash = MX25L12845EFull(connection)                                              # Construct full driver, (connection, auto_erase=False) → MX25L12845EFull
check_true('init (JEDEC C2 20 18)', flash.jedec_id() == b'\xc2\x20\x18')
check_true('electronic_id (RES) = 17h', flash.electronic_id() == 0x17)             # Read the old-style electronic ID, () → int
check_true('device_ids (REMS) = C2 17', flash.device_ids() == (0xC2, 0x17))       # Read manufacturer + device ID, () → (int, int)
sr = flash.read_status()                                                         # Read the status register, () → int
check_true('status: not busy, WEL clear', (sr & 0x03) == 0)
bp = flash.protection()                                                          # Read BP3:0, () → int
print('INFO status 0x{:02X}, BP={}'.format(sr, bp))
print('INFO security 0x{:02X} (bit0 = OTP factory locked)'.format(flash.read_security()))   # Read the security register, () → int
esn = flash.serial_number()                                                      # Read the 16-byte serial-number OTP area, () → bytes
print('INFO ESN:', esn.hex())
check_true('ESN stable', flash.serial_number() == esn)
check_true('SFDP signature', flash.read_sfdp(0, 4) == b'SFDP')                    # Read the SFDP table, (address, length) → bytes
if bp:
    print('INFO protection was BP={}; clearing for the test'.format(bp))
    flash.set_protection(0)

SCRATCH = 0xFF0000   # the last 64 KiB of the chip is used and left blank
flash.erase_sector(SCRATCH)                                                      # Erase one 4 KiB sector, (address) → None
check_true('sector blank (all FF) after erase', flash.read_bytes(SCRATCH, 4096) == b'\xff' * 4096)

page = bytes((i * 3 + 1) & 0xFF for i in range(256))
flash.page_program(SCRATCH, page)                                                # Program one page, (address, data) → None
check_true('read_bytes matches', flash.read_bytes(SCRATCH, 256) == page)
check_true('fast_read matches', flash.fast_read(SCRATCH, 256) == page)
flash.page_program(SCRATCH, bytes((0x0F, 0xF0, 0x00, 0xFF)))
back = flash.read_bytes(SCRATCH, 4)
check_true('programming only clears bits (AND)', back[0] == (page[0] & 0x0F) and back[1] == (page[1] & 0xF0) and back[2] == 0 and back[3] == page[3])
check_true('page-crossing program refused', raises(OutOfRange, lambda: flash.page_program(SCRATCH + 250, page[:10])))

data = bytes((i ^ (i >> 7)) & 0xFF for i in range(3000))
flash.write_bytes(SCRATCH + 4096 - 100, data)                                    # Erase-aware write, (address, data) → None
check_true('write_bytes across two sectors + read back', flash.read_bytes(SCRATCH + 4096 - 100, 3000) == data)
flash.write_bytes(SCRATCH + 4096 - 100, data)
check_true('identical rewrite', flash.read_bytes(SCRATCH + 4096 - 100, 3000) == data)
check_true('write past the end refused', raises(OutOfRange, lambda: flash.write_bytes(flash.size() - 2, data[:5])))

flash.erase_block_32k(SCRATCH)                                                   # Erase a 32 KiB block, (address) → None
flash.erase_block_64k(SCRATCH)                                                   # Erase a 64 KiB block, (address) → None
check_true('64 KiB block blank', flash.read_bytes(SCRATCH + 0xF000, 16) == b'\xff' * 16)

check_true('BlockDevice geometry', flash.block_size() == 512 and flash.block_count() == 32768 and flash.erase_size() == 4096 and flash.needs_erase())
blk = SCRATCH // 512
w = bytes((i * 5 + 2) & 0xFF for i in range(1024))
flash.write(blk, w)                                                              # BlockDevice write, (block, data) → None
check_true('BlockDevice read back', flash.read(blk, 2) == w)
check_true('0->1 without erase -> NotErased', raises(NotErased, lambda: flash.write(blk, b'\xff' * 512)))
flash.erase(blk, 8)                                                              # BlockDevice erase (4 KiB units), (block, count) → None
check_true('unaligned erase refused', raises(OutOfRange, lambda: flash.erase(blk + 1, 8)))
flash.sync()                                                                     # Wait until the chip is idle, () → None

flash.set_protection(1)                                                          # Protect the top 2 x 64 KiB blocks, (bp 0-15) → None
check_true('program into protected area -> WriteProtected', raises(WriteProtected, lambda: flash.page_program(SCRATCH + 0x8000, b'\x00')))
check_true('erase of protected sector -> WriteProtected', raises(WriteProtected, lambda: flash.erase_sector(SCRATCH + 0x8000)))
flash.set_protection(0)
flash.page_program(SCRATCH + 0x8000, b'\x00')
check_true('program after unprotect', flash.read_bytes(SCRATCH + 0x8000, 1) == b'\x00')

flash.deep_power_down()                                                          # Enter deep power-down, () → None
flash.release_deep_power_down()                                                  # Leave deep power-down (RDP), () → None
check_true('release_deep_power_down, RES again', flash.electronic_id() == 0x17)

flash.erase_block_64k(SCRATCH)
flash.wait_ready(1000)                                                           # Wait for WIP = 0, (timeout_ms) → None
print('===DONE: {} passed, {} failed==='.format(passed, failed))
