import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.memory.cat24c512 import CAT24C512Full

from machine import I2C, Pin

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {!r}, expected {!r}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR, reg_bytes=2)
eeprom = CAT24C512Full(connection)

check_eq('size', eeprom.size(), 65536)
check_eq('page_size', eeprom.page_size(), 128)

eeprom.write_byte(0x0010, 0x5A)
check_eq('write_byte/read_byte round-trip', eeprom.read_byte(0x0010), 0x5A)
check_true('wait_ready after write', eeprom.wait_ready(10))

DATA = bytes(range(200))
eeprom.write(0x0070, DATA)
check_eq('page-split write/read round-trip', eeprom.read(0x0070, len(DATA)), DATA)

eeprom.write_page(0x0300, bytes(range(128)))
check_eq('write_page round-trip', eeprom.read(0x0300, 128), bytes(range(128)))
try:
    eeprom.write_page(0x037C, bytes(8))
    check_true('write_page crossing rejected', False)
except ValueError:
    check_true('write_page crossing rejected', True)

eeprom.fill(0x0100, 100, 0xC3)
check_eq('fill', eeprom.read(0x0100, 100), bytes([0xC3]) * 100)
eeprom.fill(0x0100, 100)
check_eq('fill default zero', eeprom.read(0x0100, 100), bytes(100))

eeprom.write(0xFFFE, b'\x12\x34')
check_eq('last bytes round-trip', eeprom.read(0xFFFE, 2), b'\x12\x34')

try:
    eeprom.read(0xFFFF, 2)
    check_true('wrap rejected', False)
except ValueError:
    check_true('wrap rejected', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
