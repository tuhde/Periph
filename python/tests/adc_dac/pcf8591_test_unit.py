import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.pcf8591 import PCF8591Full

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
adc = PCF8591Full(connection)
check_true('init', True)

# read_channel(2): writes control byte CHN=2, then reads 2 bytes; byte0 is
# stale and discarded, byte1 is the fresh sample.
connection.queue_read(bytes([0x11, 0x7F]))
check_true('read_channel', adc.read_channel(2) == 0x7F)
check_true('read_channel_writes_control', connection.writes[-1] == bytes([0x02]))

# read_channel clamps an out-of-range channel to 0.
connection.queue_read(bytes([0x00, 0x55]))
check_true('read_channel_invalid_clamps_to_0', adc.read_channel(9) == 0x55)
check_true('read_channel_invalid_writes_ctrl_0', connection.writes[-1] == bytes([0x00]))

# read_all(): writes control byte with AI=1 (0x04), reads 5 bytes, discards
# the stale first byte.
connection.queue_read(bytes([0x00, 0x10, 0x20, 0x30, 0x40]))
check_true('read_all', adc.read_all() == [0x10, 0x20, 0x30, 0x40])
check_true('read_all_writes_ctrl', connection.writes[-1] == bytes([0x04]))

# configure(input_mode=3, auto_increment=True, dac_enabled=True):
# AIP=3<<4=0x30, AOE=1<<6=0x40, AI=1<<2=0x04, CHN=last_channel(0) -> 0x74.
adc.configure(3, True, True)
check_true('configure', connection.writes[-1] == bytes([0x74]))

# read_channel_voltage(channel, vref=3.3, vagnd=0.0): raw=128 -> V = 0 + 128*3.3/256.
connection.queue_read(bytes([0x00, 128]))
v = adc.read_channel_voltage(0, 3.3, 0.0)
check_true('read_channel_voltage', abs(v - (128 * 3.3 / 256.0)) < 1e-9)

# read_all_voltage(vref=3.3, vagnd=0.0): raws [0, 64, 128, 255].
connection.queue_read(bytes([0x00, 0, 64, 128, 255]))
voltages = adc.read_all_voltage(3.3, 0.0)
expected = [r * 3.3 / 256.0 for r in [0, 64, 128, 255]]
check_true('read_all_voltage', all(abs(a - b) < 1e-9 for a, b in zip(voltages, expected)))

# read_differential(channel=1): control byte carries current _control | ch.
# Raw byte 200 -> signed two's complement = 200 - 256 = -56.
connection.queue_read(bytes([0x00, 200]))
d = adc.read_differential(1)
check_true('read_differential_negative', d == -56)

# Raw byte 100 (< 128) stays positive.
connection.queue_read(bytes([0x00, 100]))
d2 = adc.read_differential(1)
check_true('read_differential_positive', d2 == 100)

# set_dac(200): sets AOE=1, AI=0, writes [ctrl, value].
adc.set_dac(200)
last = connection.writes[-1]
check_true('set_dac_value', last[1] == 200)
check_true('set_dac_sets_aoe', (last[0] & 0x40) != 0)
check_true('set_dac_clears_ai', (last[0] & 0x04) == 0)

# set_dac clamps to [0, 255].
adc.set_dac(9000)
check_true('set_dac_clamps_high', connection.writes[-1][1] == 255)
adc.set_dac(-10)
check_true('set_dac_clamps_low', connection.writes[-1][1] == 0)

# set_dac_voltage(0.5) -> value = round(0.5*255) = 128.
adc.set_dac_voltage(0.5)
check_true('set_dac_voltage', connection.writes[-1][1] == 128)

# disable_dac(): clears AOE bit.
adc.disable_dac()
check_true('disable_dac', (connection.writes[-1][0] & 0x40) == 0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
