from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.led.ht16k33 import HT16K33Full

passed = 0
failed = 0


def check(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def raises(fn):
    try:
        fn()
    except ValueError:
        return True
    return False


c = I2CConnectionMock()
d = HT16K33Full(c)
check('init_osc', c.writes[0] == bytes([0x21]))
check('init_row', c.writes[1] == bytes([0xA0]))
check('init_dim', c.writes[2] == bytes([0xEF]))
check('init_ram', c.writes[3] == bytes([0x00] + [0] * 16))
check('init_display', c.writes[4] == bytes([0x81]))

d.set_pixel(1, 9)
d.show()
exp = bytearray(16)
exp[3] = 0x02
check('set_pixel_show', c.writes[-1] == bytes([0x00]) + bytes(exp))
check('get_pixel', d.get_pixel(1, 9) and not d.get_pixel(1, 8))
d.set_pixel(7, 15)
check('set_pixel_last', d._ram[15] == 0x80)
d.set_pixel(1, 9, False)
check('clear_pixel', not d.get_pixel(1, 9))
check('com_range', raises(lambda: d.set_pixel(8, 0)))
check('row_range', raises(lambda: d.set_pixel(0, 16)))

d.clear()
check('clear', c.writes[-1] == bytes([0x00] + [0] * 16))
d.write_raw(b'\xAB\xCD', 4)
check('write_raw', c.writes[-1] == bytes([0x04, 0xAB, 0xCD]) and d._ram[5] == 0xCD)
check('write_raw_overflow', raises(lambda: d.write_raw(b'\x00' * 3, 14)))
check('write_raw_empty', raises(lambda: d.write_raw(b'')))

d.set_brightness(7)
check('brightness', c.writes[-1] == bytes([0xE7]))
check('brightness_range', raises(lambda: d.set_brightness(16)))

d.set_blink(1)
check('blink_1hz', c.writes[-1] == bytes([0x85]))
d.set_blink(0.5)
check('blink_0_5hz', c.writes[-1] == bytes([0x87]))
d.set_blink(2)
check('blink_2hz', c.writes[-1] == bytes([0x83]))
check('blink_invalid', raises(lambda: d.set_blink(3)))
d.display_off()
check('display_off_keeps_blink', c.writes[-1] == bytes([0x82]))
d.display_on()
check('display_on', c.writes[-1] == bytes([0x83]))
d.set_blink(0)

d.set_int_output(True)
check('int_low', c.writes[-1] == bytes([0xA1]))
d.set_int_output(True, True)
check('int_high', c.writes[-1] == bytes([0xA3]))

d.standby()
check('standby', c.writes[-1] == bytes([0x20]))
n = len(c.writes)
d.wake()
check('wake_sequence', c.writes[n] == bytes([0x21]) and c.writes[n + 1] == bytes([0xA3])
      and c.writes[n + 2] == bytes([0xE7]) and len(c.writes[n + 3]) == 17
      and c.writes[n + 4] == bytes([0x81]))

c.queue_read(bytes([0x01, 0x00, 0x00, 0x10, 0x00, 0x00]))
keys = d.read_keys()
check('read_keys_ptr', c.writes[-1] == bytes([0x40]))
check('read_keys_mask', keys == (1 | (1 << (13 + 12))))
c.queue_read(bytes([0x01]))
check('key_pressed', d.key_pressed() and c.writes[-1] == bytes([0x60]))
c.queue_read(bytes([0x00]))
check('poll_none', d.poll_interrupt() == 0)
c.queue_read(bytes([0x01]))
c.queue_read(bytes([0, 0, 0, 0, 0x01, 0]))
check('poll_keys', d.poll_interrupt() == (1 << 26))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
