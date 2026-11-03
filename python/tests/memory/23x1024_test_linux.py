"""Linux hardware test for the 23X1024 serial SRAM — runs against real hardware.

Writes and reads back single bytes and blocks, exercises fill and the
Byte / Page / Sequential modes.
"""

import os
from periph.connection.spi_linux import SPIConnection
from periph.chips.memory._23x1024 import SRAM23X1024Full

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


SPI_BUS = int(os.environ.get('SPI_BUS', '0'))
SPI_DEVICE = int(os.environ.get('SPI_DEVICE', '0'))

connection = SPIConnection(SPI_BUS, SPI_DEVICE, mode=0, max_speed_hz=8_000_000)

sram = SRAM23X1024Full(connection)                                                # Construct full driver, (connection) → SRAM23X1024Full
check_true('init_mode_roundtrip', isinstance(sram, SRAM23X1024Full))              # constructor writes MODE=0x40 and verifies it with RDMR
check_true('size', sram.size() == 131072)
check_true('default_mode_sequential', sram.get_mode() == SRAM23X1024Full.MODE_SEQUENTIAL)

sram.write_byte(0x00000, 0xA5)                                                    # Write one byte, (address 0–131071, value 0–255) → None
check_true('byte_roundtrip_first', sram.read_byte(0x00000) == 0xA5)               # Read one byte, (address 0–131071) → int
sram.write_byte(0x1FFFF, 0x5A)
check_true('byte_roundtrip_last', sram.read_byte(0x1FFFF) == 0x5A)
sram.write_byte(0x10000, 0x3C)
check_true('address_bit16_distinct', sram.read_byte(0x10000) == 0x3C and sram.read_byte(0x00000) == 0xA5)

pattern = bytes((i * 7 + 3) & 0xFF for i in range(1000))
sram.write(0x00400, pattern)                                                      # Block write, (address, data) → None
check_true('block_roundtrip_chunked', sram.read(0x00400, 1000) == pattern)        # Block read, (address, length) → bytes

sram.fill(0x02000, 100, 0xC3)                                                     # Fill a range, (address, length, value=0) → None
check_true('fill_range', sram.read(0x02000, 100) == bytes([0xC3]) * 100 and sram.read_byte(0x02064) != 0xC3)

sram.write(0x1FFF0, pattern[:16])
check_true('block_at_array_end', sram.read(0x1FFF0, 16) == pattern[:16])

sram.set_mode(SRAM23X1024Full.MODE_PAGE)                                          # Select operating mode, (mode=MODE_PAGE=0x80) → None
check_true('set_mode_page', sram.get_mode() == SRAM23X1024Full.MODE_PAGE)
sram.write(0x04010, pattern[:64])                                                 # crosses the pages at 0x4020 and 0x4040
check_true('page_mode_crossing_linear', sram.read(0x04010, 64) == pattern[:64])

sram.set_mode(SRAM23X1024Full.MODE_BYTE)
check_true('set_mode_byte', sram.get_mode() == SRAM23X1024Full.MODE_BYTE)
sram.write(0x05000, pattern[:8])
check_true('byte_mode_block', sram.read(0x05000, 8) == pattern[:8])

sram.set_mode(SRAM23X1024Full.MODE_SEQUENTIAL)
check_true('set_mode_sequential', sram.get_mode() == SRAM23X1024Full.MODE_SEQUENTIAL)

sram.reset_io()                                                                   # Return to SPI mode, () → None
check_true('reset_io_stays_spi', sram.get_mode() == SRAM23X1024Full.MODE_SEQUENTIAL)
check_true('data_survives_reset_io', sram.read_byte(0x00000) == 0xA5)

connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
