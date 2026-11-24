from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.led.saa1064 import SAA1064Full

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
d = SAA1064Full(c)
check('init', c.writes[0] == bytes([0x00, 0x47, 0, 0, 0, 0]))

d.show_digits([1, 2, 3, 4])
check('show_digits', c.writes[-1] == bytes([0x01, 0x06, 0x5B, 0x4F, 0x66]))
d.show_digits([None, 0, 9, 8])
check('show_digits_blank', c.writes[-1] == bytes([0x01, 0x00, 0x3F, 0x6F, 0x7F]))
check('show_digits_range', raises(lambda: d.show_digits([10, 0, 0, 0])))
check('show_digits_len', raises(lambda: d.show_digits([1, 2, 3])))

d.show_number(42)
check('show_number', c.writes[-1] == bytes([0x01, 0x00, 0x00, 0x66, 0x5B]))
d.show_number(0)
check('show_number_zero', c.writes[-1] == bytes([0x01, 0x00, 0x00, 0x00, 0x3F]))
d.show_number(9999)
check('show_number_max', c.writes[-1] == bytes([0x01, 0x6F, 0x6F, 0x6F, 0x6F]))
check('show_number_range', raises(lambda: d.show_number(10000)) and raises(lambda: d.show_number(-1)))

d.clear()
check('clear', c.writes[-1] == bytes([0x01, 0, 0, 0, 0]))
d.write_raw([0xAA, 0x55], 2)
check('write_raw', c.writes[-1] == bytes([0x02, 0xAA, 0x55]))
check('write_raw_overflow', raises(lambda: d.write_raw([1, 2, 3], 3)))
check('write_raw_first', raises(lambda: d.write_raw([1], 0)) and raises(lambda: d.write_raw([1], 5)))
check('write_raw_empty', raises(lambda: d.write_raw([])))

d.set_mode(False)
check('mode_static', c.writes[-1] == bytes([0x00, 0x46]))
d.set_mode(True)
check('mode_dynamic', c.writes[-1] == bytes([0x00, 0x47]))
d.blank(True, False)
check('blank_13', c.writes[-1] == bytes([0x00, 0x45]))
d.blank(True, True)
check('blank_both', c.writes[-1] == bytes([0x00, 0x41]))
d.blank(False, False)
check('blank_none', c.writes[-1] == bytes([0x00, 0x47]))
d.set_current(21)
check('current_21', c.writes[-1] == bytes([0x00, 0x77]))
d.set_current(0)
check('current_0', c.writes[-1] == bytes([0x00, 0x07]))
d.set_current(12)
check('current_12', c.writes[-1] == bytes([0x00, 0x47]))
check('current_invalid', raises(lambda: d.set_current(10)))
d.segment_test(True)
check('test_on', c.writes[-1] == bytes([0x00, 0x4F]))
d.segment_test(False)
check('test_off', c.writes[-1] == bytes([0x00, 0x47]))
d.write_control(0xFF)
check('write_control_bit7', c.writes[-1] == bytes([0x00, 0x7F]))
d.write_control(0x47)

d.show_digit(2, 7, True)
check('show_digit_dp', c.writes[-1] == bytes([0x02, 0x87]))
d.set_decimal_point(2, False)
check('dp_off', c.writes[-1] == bytes([0x02, 0x07]))
d.set_decimal_point(3, True)
check('dp_on', c.writes[-1] == bytes([0x03, 0xD5]))
check('show_digit_position', raises(lambda: d.show_digit(5, 1)))
check('dp_position', raises(lambda: d.set_decimal_point(0, True)))

d.set_segment_map({'a': 7, 'b': 6, 'c': 5, 'd': 4, 'e': 3, 'f': 2, 'g': 1, 'dp': 0})
d.show_digits([1, 1, 1, 1])
check('segment_map', c.writes[-1] == bytes([0x01, 0x60, 0x60, 0x60, 0x60]))
check('segment_map_missing', raises(lambda: d.set_segment_map({'a': 0})))
check('segment_map_range', raises(lambda: d.set_segment_map({'a': 8, 'b': 1, 'c': 2, 'd': 3, 'e': 4, 'f': 5, 'g': 6, 'dp': 7})))

c.queue_read(bytes([0x80]))
check('read_status', d.read_status() == 0x80)
c.queue_read(bytes([0x80]))
check('power_reset_flag_set', d.power_reset_flag() is True)
c.queue_read(bytes([0x00]))
check('power_reset_flag_clear', d.power_reset_flag() is False)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
