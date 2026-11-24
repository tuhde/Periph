import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.humidity.hts221 import HTS221Full
from machine import I2C, Pin

passed = 0
failed = 0

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


sensor = HTS221Full(connection)
check_true('who_am_i', sensor.who_am_i() == 0xBC)
check_true('calibration_plausible', sensor.read_calibration()['H1_rH'] > sensor.read_calibration()['H0_rH'])
m = sensor.read()
check_true('read_temperature_range', -40 < m['temperature_c'] < 85)
check_true('read_humidity_range', 0 <= m['humidity_pct'] <= 100)
check_true('read_humidity_single', 0 <= sensor.read_humidity() <= 100)
check_true('read_temperature_single', -40 < sensor.read_temperature() < 85)
check_true('dew_point_below_temperature', sensor.dew_point() <= sensor.read_temperature() + 1)
sensor.set_averaging(32, 64)
check_true('averaging_roundtrip', sensor.get_averaging() == (32, 64))
sensor.set_averaging(16, 32)
sensor.set_output_data_rate(1)
check_true('odr_roundtrip', sensor.get_output_data_rate() == 1.0)
time.sleep(1.5)
check_true('continuous_read', 0 <= sensor.read_continuous()['humidity_pct'] <= 100)
sensor.set_output_data_rate(0)
sensor.set_heater(True)
check_true('heater_on', sensor.is_heater_on())
sensor.set_heater(False)
check_true('heater_off', not sensor.is_heater_on())

print('===DONE: %d passed, %d failed===' % (passed, failed))
