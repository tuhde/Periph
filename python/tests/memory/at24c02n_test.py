import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.memory.at24c02n import AT24C02NFull

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
connection = I2CConnection(i2c, cfg.ADDR)
eeprom = AT24C02NFull(connection)

check_eq('size', eeprom.size(), 256)
check_eq('page_size', eeprom.page_size(), 8)

eeprom.write_byte(0x0010, 0x5A)
check_eq('write_byte/read_byte round-trip', eeprom.read_byte(0x0010), 0x5A)
check_true('wait_ready after write', eeprom.wait_ready(10))

DATA = bytes(range(100))
eeprom.write(0x0070, DATA)
check_eq('page-split write/read round-trip', eeprom.read(0x0070, len(DATA)), DATA)

eeprom.write_page(0x00C0, bytes(range(8)))
check_eq('write_page round-trip', eeprom.read(0x00C0, 8), bytes(range(8)))
try:
    eeprom.write_page(0x00CC, bytes(8))
    check_true('write_page crossing rejected', False)
except ValueError:
    check_true('write_page crossing rejected', True)

eeprom.fill(0x0040, 100, 0xC3)
check_eq('fill', eeprom.read(0x0040, 100), bytes([0xC3]) * 100)
eeprom.fill(0x0040, 100)
check_eq('fill default zero', eeprom.read(0x0040, 100), bytes(100))

eeprom.write(0x00FE, b'\x12\x34')
check_eq('last bytes round-trip', eeprom.read(0x00FE, 2), b'\x12\x34')

try:
    eeprom.read(0x00FF, 2)
    check_true('wrap rejected', False)
except ValueError:
    check_true('wrap rejected', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
