"""MicroPython hardware test for the SD card (SPI mode) — runs against real hardware.

Initialises the card, reads CID/OCR, and round-trips single and multi-block
transfers in the last 8 blocks of the card (contents are saved and restored).
"""

import machine
import _testconfig as cfg
from periph.connection.spi_micropython import SPIConnection
from periph.chips.memory.sd_spi import SDSPIFull
from periph.storage.block_device import OutOfRange

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


spi = machine.SPI(cfg.SPI_ID, sck=cfg.SCK, mosi=cfg.MOSI, miso=cfg.MISO,
                  baudrate=400000)
cs = machine.Pin(cfg.CS, machine.Pin.OUT, value=1)
connection = SPIConnection(spi, cs)

sd = SDSPIFull(connection)                                                        # Construct full driver, (connection, speed_hz=12000000, crc=True) → SDSPIFull
check_true('init', sd.card_type() != 0)                                           # constructor runs CMD0/CMD8/ACMD41/CMD58 and reads the CSD
info = sd.info()                                                                  # Geometry and capabilities, () → dict
print('type={} blocks={} name={}'.format(sd.card_type(), info['block_count'], info['name']))
check_true('geometry', info['block_size'] == 512 and info['block_count'] > 0)
check_true('no_erase_needed', sd.erase_size() == 512 and not sd.needs_erase())
check_true('is_present', sd.is_present())                                         # Card responds, () → bool
check_true('cid_read', len(sd.cid()) == 16)                                       # Read CID register, () → bytes
check_true('ocr_has_3v3_window', (sd.ocr() & 0x00FF8000) != 0)                    # Read OCR, () → int

# Scratch area: the last 8 blocks, contents saved and restored.
scratch = sd.block_count() - 8
saved = sd.read(scratch, 8)                                                       # Read blocks, (block, count) → bytes
check_true('save_scratch', len(saved) == 8 * 512)
pat = bytes((i * 7 + 3) & 0xFF for i in range(8 * 512))

sd.write(scratch, pat[:512])                                                      # Write blocks, (block, data) → None
check_true('single_roundtrip', sd.read(scratch, 1) == pat[:512])
sd.write(scratch, pat)
sd.sync()                                                                         # Wait until programming is done, () → None
check_true('multi_roundtrip', sd.read(scratch, 8) == pat)
try:
    sd.read(sd.block_count(), 1)
    check_true('out_of_range', False)
except OutOfRange:
    check_true('out_of_range', True)

sd.set_crc(False)                                                                 # Disable CRC checking, (enabled) → None
check_true('read_with_crc_off', sd.read(scratch, 1) == pat[:512])
sd.set_crc(True)
check_true('set_speed_8mhz', sd.set_speed(8000000))                               # Change SCK frequency, (hz) → bool
check_true('read_at_higher_speed', sd.read(scratch, 8) == pat)

sd.write(scratch, saved)                                                          # restore the original contents
sd.sync()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
