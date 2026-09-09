import sys

from periph.connection.hx711_mock import HX711ConnectionMock
from periph.chips.adc_dac.hx711 import HX711Minimal, HX711Full

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


# --- HX711Minimal ---

connection = HX711ConnectionMock()
connection.queue_read(0)  # discarded by init()
sensor = HX711Minimal(connection)
check_true('init_discards_first_reading', connection.reads == [25])

connection.ready = False
check_true('is_ready_false', sensor.is_ready() is False)
connection.ready = True
check_true('is_ready_true', sensor.is_ready() is True)

connection.queue_read(12345)
check_true('read_raw_gain_128', sensor.read_raw() == 12345)
check_true('read_raw_uses_25_pulses', connection.reads[-1] == 25)

# --- HX711Full ---

connection = HX711ConnectionMock()
connection.queue_read(0)
sensor = HX711Full(connection)
check_true('full_init_discards_first_reading', connection.reads == [25])

connection.queue_read(1000)
check_true('full_read_raw_default_gain_128', sensor.read_raw() == 1000)
check_true('full_read_raw_default_25_pulses', connection.reads[-1] == 25)

# set_gain(): selects pulse count and issues one dummy read to apply it.
connection.queue_read(0)  # dummy read issued by set_gain(64)
sensor.set_gain(64)
check_true('set_gain_64_issues_dummy_read', connection.reads[-1] == 27)
connection.queue_read(2000)
sensor.read_raw()
check_true('set_gain_64_pulses', connection.reads[-1] == 27)

connection.queue_read(0)  # dummy read issued by set_gain(32)
sensor.set_gain(32)
check_true('set_gain_32_issues_dummy_read', connection.reads[-1] == 26)
connection.queue_read(3000)
sensor.read_raw()
check_true('set_gain_32_pulses', connection.reads[-1] == 26)

connection.queue_read(0)  # dummy read issued by set_gain(128)
sensor.set_gain(128)
check_true('set_gain_128_issues_dummy_read', connection.reads[-1] == 25)

try:
    sensor.set_gain(99)
    check_true('set_gain_invalid_raises', False)
except ValueError:
    check_true('set_gain_invalid_raises', True)

# read_average(): mean of `times` raw readings, integer division.
connection.queue_read(10)
connection.queue_read(20)
connection.queue_read(33)
check_true('read_average', sensor.read_average(3) == (10 + 20 + 33) // 3)

# tare(): captures read_average() as the offset.
connection.queue_read(100)
connection.queue_read(100)
sensor.tare(2)
check_true('tare_sets_offset', sensor.get_offset() == 100)

# set_scale()/get_scale().
sensor.set_scale(2.5)
check_true('set_scale', sensor.get_scale() == 2.5)

# read_weight(): (read_average(times) - offset) / scale.
connection.queue_read(350)
check_true('read_weight', sensor.read_weight(1) == (350 - 100) / 2.5)

# power_down()/power_up(): power_up() resets pulse count to 25 and discards
# one reading, even if a non-default gain was previously selected.
connection.queue_read(0)  # dummy read issued by set_gain(64)
sensor.set_gain(64)
sensor.power_down()
check_true('power_down_calls_connection', connection.power_calls[-1] == 'down')
connection.queue_read(0)  # discarded by power_up()
sensor.power_up()
check_true('power_up_calls_connection', connection.power_calls[-1] == 'up')
check_true('power_up_resets_gain', connection.reads[-1] == 25)
connection.queue_read(4242)
sensor.read_raw()
check_true('power_up_read_raw_uses_25_pulses', connection.reads[-1] == 25)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
