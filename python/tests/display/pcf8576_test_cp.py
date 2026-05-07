import time
import busio
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.display.pcf8576 import PCF8576Minimal, PCF8576Full

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got 0x{:04X}, expected 0x{:04X}'.format(label, got, expected))
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
lcd = PCF8576Full(transport)

lcd.clear()
check_true('clear: no exception', True)

lcd.set_digit_7seg(0, PCF8576Full.SEG_7SEG[0])
check_true('set_digit_7seg: no exception', True)

lcd.write_raw(0, bytes([0xED, 0x60]))
check_true('write_raw: no exception', True)

lcd.enable()
check_true('enable: no exception', True)

lcd.disable()
check_true('disable: no exception', True)

lcd.set_mode(4, 0)
check_true('set_mode: no exception', True)

lcd.set_blink(PCF8576Full.BLINK_OFF)
check_true('set_blink: no exception', True)

lcd.set_bank(0, 0)
check_true('set_bank: no exception', True)

lcd.device_select(0)
check_true('device_select: no exception', True)

i2c.deinit()

print('===DONE: {} passed, {} failed==='.format(passed, failed))