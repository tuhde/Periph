import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.led.ht16k33 import HT16K33Minimal, HT16K33Full

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x70'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

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


matrix = HT16K33Minimal(connection)
check('constants', HT16K33Minimal.COMMONS == 8 and HT16K33Minimal.ROWS == 16)
attempt('set_pixel_show', lambda: (matrix.set_pixel(0, 0), matrix.set_pixel(7, 15), matrix.show()))
attempt('write_raw', lambda: matrix.write_raw(bytes([0xFF, 0x00]), 0))
attempt('set_brightness', lambda: (matrix.set_brightness(0), matrix.set_brightness(15)))
attempt('clear', matrix.clear)

full = HT16K33Full(connection)
full.set_pixel(3, 4)
full.show()
check('get_pixel', full.get_pixel(3, 4) and not full.get_pixel(3, 5))
attempt('blink', lambda: (full.set_blink(2), full.set_blink(1), full.set_blink(0.5), full.set_blink(0)))
attempt('display_off_on', lambda: (full.display_off(), full.display_on()))
attempt('standby_wake', lambda: (full.standby(), full.wake()))
attempt('set_int_output', lambda: (full.set_int_output(True), full.set_int_output(True, True), full.set_int_output(False)))
attempt('read_keys', full.read_keys)
attempt('key_pressed', full.key_pressed)
full.clear()

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
