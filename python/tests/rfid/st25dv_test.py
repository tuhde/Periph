import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.rfid.st25dv import ST25DVFull

from machine import I2C, Pin

SYSTEM_ADDR = 0x57

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
user = I2CConnection(i2c, cfg.ADDR, reg_bytes=2)
system = I2CConnection(i2c, SYSTEM_ADDR, reg_bytes=2)
tag = ST25DVFull(user, system)

check_true('ic_reference', tag.ic_reference() in (0x24, 0x26))
check_true('memory_size', tag.memory_size() in (512, 2048, 8192))
uid = tag.uid()
check_eq('uid length', len(uid), 8)
check_eq('uid manufacturer', uid[0:2], b'\xE0\x02')
check_true('ic_revision', 0 <= tag.ic_revision() <= 255)

addr = tag.memory_size() - 64
saved = tag.read(addr, 40)
pattern = bytes((i * 7 + 3) & 0xFF for i in range(40))
tag.write(addr, pattern)
check_eq('write/read round-trip', tag.read(addr, 40), pattern)
tag.write(addr, saved)
check_eq('restore', tag.read(addr, 40), saved)

check_true('session_open is bool', tag.session_open() in (True, False))
check_true('present factory password', tag.present_password(bytes(8)))
check_true('session open after present', tag.session_open())

prot = tag.get_area_protection(1)
check_eq('get_area_protection tuple', len(prot), 2)
check_true('get_gpo', 0 <= tag.get_gpo() <= 255)
check_true('field_present is bool', tag.field_present() in (True, False))
check_true('poll_interrupt', 0 <= tag.poll_interrupt() <= 255)
check_true('mailbox_status', 'enabled' in tag.mailbox_status())
check_true('read_ndef', isinstance(tag.read_ndef(), bytes))
tag.wait_write_complete()
check_true('wait_write_complete', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
