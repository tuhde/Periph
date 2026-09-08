import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.display.pcf8576 import PCF8576Full

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


connection = I2CConnectionMock()
sensor = PCF8576Full(connection)

# init: mode-set (E=1, bias=1/3, mode=1:4 -> 0x40|0x08|0x00|0x00 = 0x48),
# then load-ptr(0) + 20 zero bytes to blank all RAM.
check_true('init_mode_write', connection.writes[0] == bytes([0x48]))
check_true('init_clear_write', connection.writes[1] == bytes([0x00] + [0] * 20))

# clear()
sensor.clear()
check_true('clear_mode_write', connection.writes[-2] == bytes([0x48]))
check_true('clear_data_write', connection.writes[-1] == bytes([0x00] + [0] * 20))

# write_raw()
sensor.write_raw(5, bytes([0xAB, 0xCD]))
check_true('write_raw', connection.writes[-1] == bytes([0x05, 0xAB, 0xCD]))

n_before = len(connection.writes)
sensor.write_raw(3, b'')
check_true('write_raw_empty_is_noop', len(connection.writes) == n_before)

try:
    sensor.write_raw(-1, b'\x01')
    check_true('write_raw_negative_raises', False)
except ValueError:
    check_true('write_raw_negative_raises', True)

try:
    sensor.write_raw(40, b'\x01')
    check_true('write_raw_too_high_raises', False)
except ValueError:
    check_true('write_raw_too_high_raises', True)

# set_digit_7seg(): digit '7' -> 0xE0, at RAM address 3*2=6.
sensor.set_digit_7seg(3, PCF8576Full._SEVEN_SEG[7])
check_true('set_digit_7seg', connection.writes[-1] == bytes([0x06, 0xE0]))

try:
    sensor.set_digit_7seg(20, 0x00)
    check_true('set_digit_7seg_too_high_raises', False)
except ValueError:
    check_true('set_digit_7seg_too_high_raises', True)

# Full: enable/disable
sensor.disable()
check_true('disable_writes_mode', connection.writes[-1] == bytes([0x40]))
sensor.enable()
check_true('enable_writes_mode', connection.writes[-1] == bytes([0x48]))

# set_mode(): mode-set byte = 0x40 | E(0x08) | bias | mode.
sensor.set_mode(PCF8576Full.BACKPLANES_1, PCF8576Full.BIAS_1_2)
check_true('set_mode_static_bias_1_2', connection.writes[-1] == bytes([0x4D]))  # 0x40|8|4|1

sensor.set_mode(PCF8576Full.BACKPLANES_2, PCF8576Full.BIAS_1_3)
check_true('set_mode_1_2', connection.writes[-1] == bytes([0x4A]))  # 0x40|8|0|2

sensor.set_mode(PCF8576Full.BACKPLANES_3, PCF8576Full.BIAS_1_3)
check_true('set_mode_1_3', connection.writes[-1] == bytes([0x4B]))  # 0x40|8|0|3

sensor.set_mode(PCF8576Full.BACKPLANES_4, PCF8576Full.BIAS_1_3)
check_true('set_mode_1_4', connection.writes[-1] == bytes([0x48]))  # 0x40|8|0|0

# set_blink()
sensor.set_blink(PCF8576Full.BLINK_1_HZ)
check_true('set_blink', connection.writes[-1] == bytes([0x72]))  # 0x70|0|2

sensor.set_blink(PCF8576Full.BLINK_2_HZ, alternate_bank=True)
check_true('set_blink_alternate_bank', connection.writes[-1] == bytes([0x75]))  # 0x70|4|1

try:
    sensor.set_blink(4)
    check_true('set_blink_invalid_raises', False)
except ValueError:
    check_true('set_blink_invalid_raises', True)

# set_bank()
sensor.set_bank(1, 0)
check_true('set_bank', connection.writes[-1] == bytes([0x7A]))  # 0x78|(1<<1)|0

try:
    sensor.set_bank(2, 0)
    check_true('set_bank_invalid_raises', False)
except ValueError:
    check_true('set_bank_invalid_raises', True)

# device_select()
sensor.device_select(5)
check_true('device_select', connection.writes[-1] == bytes([0x65]))  # 0x60|5

try:
    sensor.device_select(8)
    check_true('device_select_invalid_raises', False)
except ValueError:
    check_true('device_select_invalid_raises', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
