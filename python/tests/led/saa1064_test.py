from machine import I2C, Pin
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.led.saa1064 import SAA1064Minimal, SAA1064Full

passed = 0
failed = 0
i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

def check(label, ok, extra=''):
    global passed, failed
    if ok:
        print('PASS ' + label)
        passed += 1
    else:
        print('FAIL ' + label + (': ' + extra if extra else ''))
        failed += 1


def attempt(label, fn):
    try:
        fn()
        check(label, True)
    except Exception as e:
        check(label, False, str(e))


display = SAA1064Minimal(connection)
check('constants', SAA1064Minimal.DIGITS == 4)
attempt('show_digits', lambda: display.show_digits([1, 2, 3, 4]))
attempt('show_number', lambda: display.show_number(1234))
attempt('write_raw', lambda: display.write_raw([0x7F, 0x7F], 3))
attempt('clear', display.clear)

full = SAA1064Full(connection)
attempt('set_mode', lambda: (full.set_mode(False), full.set_mode(True)))
attempt('blank', lambda: (full.blank(True, False), full.blank(False, True), full.blank(False, False)))
attempt('set_current', lambda: (full.set_current(3), full.set_current(21), full.set_current(12)))
attempt('segment_test', lambda: (full.segment_test(True), full.segment_test(False)))
attempt('set_segment_map', lambda: full.set_segment_map({'a': 0, 'b': 1, 'c': 2, 'd': 3, 'e': 4, 'f': 5, 'g': 6, 'dp': 7}))
attempt('show_digit', lambda: full.show_digit(1, 5, True))
attempt('set_decimal_point', lambda: (full.set_decimal_point(1, False), full.set_decimal_point(4, True)))
attempt('read_status', lambda: full.read_status())
check('power_reset_flag_cleared', full.power_reset_flag() is False)
attempt('write_control', lambda: full.write_control(0x47))
full.clear()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
