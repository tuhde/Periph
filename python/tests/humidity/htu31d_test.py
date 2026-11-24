import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.humidity.htu31d import HTU31DFull
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


sensor = HTU31DFull(connection)
sensor.init()
m = sensor.read()
check_true('read_temperature_range', -40 < m['temperature_c'] < 125)
check_true('read_humidity_range', 0 <= m['humidity_pct'] <= 100)
check_true('read_humidity_single', 0 <= sensor.read_humidity() <= 100)
check_true('read_temperature_single', -40 < sensor.read_temperature() < 125)
rt, rr = sensor.read_raw()
check_true('read_raw_range', 0 <= rt <= 65535 and 0 <= rr <= 65535)
check_true('dew_point_below_temperature', sensor.dew_point() <= sensor.read_temperature() + 1)
check_true('serial_number_24bit', 0 <= sensor.serial_number() <= 0xFFFFFF)
check_true('diagnostic_no_nvm_error', not sensor.diagnostic_flags()['nvm_err'])
for osr in (0, 1, 2, 3):
    sensor.set_resolution(osr, osr)
    check_true('resolution_roundtrip_%d' % osr, sensor.get_resolution() == (osr, osr))
    r = sensor.read()
    check_true('read_at_osr_%d' % osr, 0 <= r['humidity_pct'] <= 100 and -40 < r['temperature_c'] < 125)
sensor.set_resolution(3, 3)
check_true('conversion_time_osr33', abs(sensor.conversion_time() - 0.02032) < 1e-6)
sensor.start_conversion()
time.sleep(0.03)
r = sensor.read_result()
check_true('start_conversion_read_result', 0 <= r['humidity_pct'] <= 100)
sensor.set_heater(True)
check_true('heater_on', sensor.is_heater_on())
sensor.set_heater(False)
check_true('heater_off', not sensor.is_heater_on())
sensor.software_reset()
check_true('read_after_reset', 0 <= sensor.read()['humidity_pct'] <= 100)

print('===DONE: %d passed, %d failed===' % (passed, failed))
