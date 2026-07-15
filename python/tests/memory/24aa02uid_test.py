import time
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.memory._24aa02uid import EEPROM24AA02UIDFull

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


def check_eq_hex(label, got, expected):
    check_eq(label, got, expected)


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
eeprom = EEPROM24AA02UIDFull(transport)

# --- Read the immutable region ---
uid = eeprom.read_uid()
check_eq('read_uid length', len(uid), 4)
check_eq('read_manufacturer_code', eeprom.read_manufacturer_code(), 0x29)
check_eq('read_device_code',       eeprom.read_device_code(),       0x41)

# --- User EEPROM byte read/write round-trip ---
TEST_ADDR  = 0x10
TEST_VALUE = 0x5A
eeprom.write_byte(TEST_ADDR, TEST_VALUE)
check_eq('write_byte/read_byte round-trip', eeprom.read_byte(TEST_ADDR), TEST_VALUE)

# --- Page write (within one 8-byte page) ---
PAGE_ADDR = 0x40
PAGE_DATA = b'\x11\x22\x33\x44'
eeprom.write_page(PAGE_ADDR, PAGE_DATA)
check_eq('write_page read-back', eeprom.read(PAGE_ADDR, len(PAGE_DATA)), PAGE_DATA)

# --- Arbitrary-length write crossing one page boundary ---
CROSS_ADDR = 0x06
CROSS_DATA = b'\xAA\xBB\xCC\xDD\xEE\xFF'   # spans 0x06-0x0B (crosses page 0)
eeprom.write(CROSS_ADDR, CROSS_DATA)
check_eq('cross-page write read-back', eeprom.read(CROSS_ADDR, len(CROSS_DATA)), CROSS_DATA)

# --- Sequential read of an arbitrary range ---
RANGE_ADDR = 0x50
RANGE_LEN  = 16
read_back = eeprom.read(RANGE_ADDR, RANGE_LEN)
check_eq('read length', len(read_back), RANGE_LEN)

# --- Verify UID did not change after the user-EEPROM writes ---
check_eq('uid unchanged after writes', eeprom.read_uid(), uid)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
