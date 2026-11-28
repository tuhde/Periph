import busio
import time
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.gas.rrh62000 import RRH62000Full

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


sensor = RRH62000Full(connection)
sensor.init()
fw = sensor.firmware_version()
check_true('firmware_version', len(fw) == 2)
check_true('algorithm_version', len(sensor.algorithm_version()) == 3)
check_true('unique_id', len(sensor.unique_id()) == 6)
r = sensor.read()
check_true('read_pm', 0 <= r['pm2_5_ug_m3'] <= 1000)
check_true('read_temperature', -20 <= r['temperature_c'] <= 60)
check_true('read_humidity', 0 <= r['humidity_rh'] <= 100)
check_true('read_iaq', 0 <= r['iaq'] <= 5.0)
check_true('read_eco2', r['eco2_ppm'] == 0 or 400 <= r['eco2_ppm'] <= 5000)
a = sensor.read_all()
check_true('read_all_nc', a['nc_0_3'] >= a['nc_0_5'] >= a['nc_1'])
check_true('read_status_fan_ok', not sensor.read_status()['fan'])
check_true('read_particles', len(sensor.read_particles()) == 11)
check_true('data_ready', sensor.data_ready() in (True, False))
avg = sensor.get_moving_average()
sensor.set_moving_average(20)
check_true('moving_average', sensor.get_moving_average() == 20)
sensor.set_moving_average(avg)
fan = sensor.get_fan_speed()
sensor.set_fan_speed(70)
check_true('fan_speed', sensor.get_fan_speed() == 70)
sensor.set_fan_speed(fan)
interval = sensor.get_cleaning_interval()
sensor.set_cleaning_interval(86400)
check_true('cleaning_interval', sensor.get_cleaning_interval() == 86400)
sensor.set_cleaning_interval(interval)
t = sensor.get_cleaning_time()
sensor.set_cleaning_time(12)
check_true('cleaning_time', sensor.get_cleaning_time() == 12)
sensor.set_cleaning_time(t)
check_true('mox_resistance', sensor.read_mox_resistance() > 0)
check_true('cleaning_done', sensor.cleaning_done() in (True, False))
sensor.sleep()
time.sleep(3)
sensor.wake()
check_true('wake_read', 'iaq' in sensor.read())
sensor.soft_reset()
check_true('soft_reset_read', 'iaq' in sensor.read())
print('===DONE: %d passed, %d failed===' % (passed, failed))
