import time
import busio
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.memory._24aa02uid import EEPROM24AA02UIDFull

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


i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
eeprom = EEPROM24AA02UIDFull(transport)

uid = eeprom.read_uid()
check_eq('read_uid length', len(uid), 4)
check_eq('read_manufacturer_code', eeprom.read_manufacturer_code(), 0x29)
check_eq('read_device_code',       eeprom.read_device_code(),       0x41)

TEST_ADDR  = 0x10
TEST_VALUE = 0x5A
eeprom.write_byte(TEST_ADDR, TEST_VALUE)
check_eq('write_byte/read_byte round-trip', eeprom.read_byte(TEST_ADDR), TEST_VALUE)

PAGE_ADDR = 0x40
PAGE_DATA = b'\x11\x22\x33\x44'
eeprom.write_page(PAGE_ADDR, PAGE_DATA)
check_eq('write_page read-back', eeprom.read(PAGE_ADDR, len(PAGE_DATA)), PAGE_DATA)

CROSS_ADDR = 0x06
CROSS_DATA = b'\xAA\xBB\xCC\xDD\xEE\xFF'
eeprom.write(CROSS_ADDR, CROSS_DATA)
check_eq('cross-page write read-back', eeprom.read(CROSS_ADDR, len(CROSS_DATA)), CROSS_DATA)

RANGE_ADDR = 0x50
RANGE_LEN  = 16
read_back = eeprom.read(RANGE_ADDR, RANGE_LEN)
check_eq('read length', len(read_back), RANGE_LEN)

check_eq('uid unchanged after writes', eeprom.read_uid(), uid)

i2c.deinit()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
