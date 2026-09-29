import sys

from periph.connection.hx711_mock import HX711ConnectionMock
from periph.chips.adc_dac.hx710b import HX710BMinimal, HX710BFull

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


# --- HX710BMinimal ---

connection = HX711ConnectionMock()
connection.queue_read(0)  # discarded by init()
sensor = HX710BMinimal(connection)
check_true('init_discards_first_reading', connection.reads == [25])

connection.ready = False
check_true('is_ready_false', sensor.is_ready() is False)
connection.ready = True
check_true('is_ready_true', sensor.is_ready() is True)

connection.queue_read(12345)
check_true('read_raw_10sps', sensor.read_raw() == 12345)
check_true('read_raw_uses_25_pulses', connection.reads[-1] == 25)

# --- HX710BFull ---

connection = HX711ConnectionMock()
connection.queue_read(0)
sensor = HX710BFull(connection)
check_true('full_init_discards_first_reading', connection.reads == [25])

connection.queue_read(1000)
check_true('full_read_raw_default_10sps', sensor.read_raw() == 1000)
check_true('full_read_raw_default_25_pulses', connection.reads[-1] == 25)

# set_rate(): selects pulse count and issues one dummy read to apply it.
connection.queue_read(0)  # dummy read issued by set_rate(40)
sensor.set_rate(40)
check_true('set_rate_40_issues_dummy_read', connection.reads[-1] == 27)
connection.queue_read(2000)
sensor.read_raw()
check_true('set_rate_40_pulses', connection.reads[-1] == 27)

connection.queue_read(0)  # dummy read issued by set_rate(10)
sensor.set_rate(10)
check_true('set_rate_10_issues_dummy_read', connection.reads[-1] == 25)

try:
    sensor.set_rate(99)
    check_true('set_rate_invalid_raises', False)
except ValueError:
    check_true('set_rate_invalid_raises', True)

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

# Regression: read_supply_diff_raw() must clock exactly 26 pulses (the
# DVDD-AVDD channel per the HX710B pulse-count table), not 25 or 27
# (which would silently read the differential input instead).
connection.queue_read(777)
check_true('read_supply_diff_raw_value', sensor.read_supply_diff_raw() == 777)
check_true('read_supply_diff_raw_uses_26_pulses', connection.reads[-1] == 26)

# power_down()/power_up(): power_up() resets the rate to 25 pulses and
# discards one reading, even if a non-default rate was previously selected.
connection.queue_read(0)  # dummy read issued by set_rate(40)
sensor.set_rate(40)
sensor.power_down()
check_true('power_down_calls_connection', connection.power_calls[-1] == 'down')
connection.queue_read(0)  # discarded by power_up()
sensor.power_up()
check_true('power_up_calls_connection', connection.power_calls[-1] == 'up')
check_true('power_up_resets_rate', connection.reads[-1] == 25)
connection.queue_read(4242)
sensor.read_raw()
check_true('power_up_read_raw_uses_25_pulses', connection.reads[-1] == 25)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
