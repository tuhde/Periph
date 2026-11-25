import os
import sys

from periph.connection.i2c_linux import I2CConnection
from periph.chips.memory.at24c64 import AT24C64Full

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x50'), 16)

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


connection = I2CConnection(I2C_BUS, I2C_ADDR, reg_bytes=2)
eeprom = AT24C64Full(connection)

check_eq('size', eeprom.size(), 8192)
check_eq('page_size', eeprom.page_size(), 32)

eeprom.write_byte(0x0010, 0x5A)
check_eq('write_byte/read_byte round-trip', eeprom.read_byte(0x0010), 0x5A)
check_true('wait_ready after write', eeprom.wait_ready(10))

DATA = bytes(range(200))
eeprom.write(0x0070, DATA)
check_eq('page-split write/read round-trip', eeprom.read(0x0070, len(DATA)), DATA)

eeprom.write_page(0x0300, bytes(range(32)))
check_eq('write_page round-trip', eeprom.read(0x0300, 32), bytes(range(32)))
try:
    eeprom.write_page(0x037C, bytes(8))
    check_true('write_page crossing rejected', False)
except ValueError:
    check_true('write_page crossing rejected', True)

eeprom.fill(0x0100, 100, 0xC3)
check_eq('fill', eeprom.read(0x0100, 100), bytes([0xC3]) * 100)
eeprom.fill(0x0100, 100)
check_eq('fill default zero', eeprom.read(0x0100, 100), bytes(100))

eeprom.write(0x1FFE, b'\x12\x34')
check_eq('last bytes round-trip', eeprom.read(0x1FFE, 2), b'\x12\x34')

try:
    eeprom.read(0x1FFF, 2)
    check_true('wrap rejected', False)
except ValueError:
    check_true('wrap rejected', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
