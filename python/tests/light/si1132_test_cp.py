import busio
import time
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.light.si1132 import Si1132Full

passed = 0
failed = 0

i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


sensor = Si1132Full(connection)
check_true('part_id', sensor.read_calibration() is not None)
m = sensor.read()
check_true('read_uv_index_range', 0 <= m['uv_index'] < 20)
check_true('read_visible_nonneg', m['visible'] >= 0)
check_true('read_infrared_nonneg', m['infrared'] >= 0)
check_true('read_uv_index_single', 0 <= sensor.read_uv_index() < 20)
check_true('read_visible_single', sensor.read_visible() >= 0)
check_true('read_infrared_single', sensor.read_infrared() >= 0)
check_true('read_raw_keys', set(sensor.read_raw()) == {'visible', 'infrared', 'aux'})
check_true('temperature_raw', 0 <= sensor.read_temperature_raw() <= 0xFFFF)
check_true('vdd_raw', 0 <= sensor.read_vdd_raw() <= 0xFFFF)
sensor.set_gain(2, 2)
check_true('gain_read', sensor.read_visible() >= 0)
sensor.set_gain(0, 0)
sensor.set_high_range(True, True)
check_true('high_range_read', sensor.read_visible() >= 0)
sensor.set_high_range(False, False)
sensor.set_channels(True, False, False)
check_true('uv_only_read', sensor.read()['uv_index'] >= 0)
sensor.set_channels(True, True, True)
sensor.force()
time.sleep(0.05)
check_true('measurement_ready', sensor.measurement_ready())
check_true('response_no_error', sensor.read_response()[1] == 0)
sensor.poll_interrupt()
sensor.start_autonomous(0.1)
time.sleep(0.5)
check_true('autonomous_ready', sensor.measurement_ready())
sensor.pause()
sensor.start_autonomous(0)
sensor.reset()
check_true('read_after_reset', sensor.read()['visible'] >= 0)

print('===DONE: %d passed, %d failed===' % (passed, failed))
