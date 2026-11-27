import busio
import time
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.light.si1145 import Si1145Full

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

sensor = Si1145Full(connection)
sensor.init()
part_id, rev_id, seq_id = sensor.read_id()
check_true('part_id', part_id in (0x45, 0x46, 0x47))
counter, err = sensor.read_response()
check_true('response_no_error', err == 0)
m = sensor.read()
check_true('read_proximity_range', 0 <= m['proximity'] <= 0xFFFF)
check_true('read_uv_index_range', 0.0 <= m['uv_index'] < 20.0)
check_true('read_visible_positive', m['visible'] > 0)
check_true('read_infrared_positive', m['infrared'] > 0)
check_true('read_proximity_single', 0 <= sensor.read_proximity() <= 0xFFFF)
check_true('read_uv_index_single', 0.0 <= sensor.read_uv_index() < 20.0)
check_true('read_visible_single', sensor.read_visible() > 0)
check_true('read_infrared_single', sensor.read_infrared() > 0)
raw = sensor.read_raw()
check_true('read_raw_keys', sorted(raw) == ['aux', 'infrared', 'ps1', 'ps2', 'ps3', 'visible'])
check_true('read_temperature_raw', 0 < sensor.read_temperature_raw() <= 0xFFFF)
check_true('read_vdd_raw', 0 < sensor.read_vdd_raw() <= 0xFFFF)
sensor.set_led_current(1, 0)
dark = sensor.read_proximity()
sensor.set_led_current(1, 15)
lit = sensor.read_proximity()
check_true('led_current_effect', lit >= dark)
sensor.set_led_current(1, 3)
sensor.set_ps_photodiode(1, True)
sensor.set_gain(visible=2, infrared=2, proximity=0)
check_true('gain_read', sensor.read_visible() > 0)
sensor.set_gain()
sensor.set_high_range(True, True, False)
check_true('high_range_read', sensor.read_visible() >= 0)
sensor.set_high_range(False, False, False)
sensor.set_alignment()
sensor.set_uv_coefficients(Si1145Full.UCOEF_DEFAULT)
check_true('read_calibration_len', len(sensor.read_calibration()) == 12)
sensor.set_channels(True, True, True, True)
sensor.poll_interrupt()
sensor.start_autonomous(0.05)
time.sleep(0.5)
check_true('autonomous_irq', sensor.poll_interrupt() & 0x05 != 0)
sensor.pause()
sensor.force()
time.sleep(0.1)
check_true('force_irq', sensor.poll_interrupt() != 0)
sensor.enable_interrupt(Si1145Full.SOURCE_CMD)
sensor.disable_interrupt(Si1145Full.SOURCE_CMD)
sensor.reset()
counter, err = sensor.read_response()
check_true('reset_no_error', err == 0)
check_true('read_after_reset', sensor.read()['visible'] > 0)
print('===DONE: %d passed, %d failed===' % (passed, failed))
