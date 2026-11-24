import os
import time
from periph.connection.i2c_linux import I2CConnection
from periph.chips.light.bh1750 import BH1750Full

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x23'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


sensor = BH1750Full(connection)
sensor.init()
check_true('read_range', 0 <= sensor.read() <= 120000)
raw = sensor.read_raw()
check_true('read_raw_range', 0 <= raw <= 65535)
for name, mode in (('h', BH1750Full.MODE_H), ('h2', BH1750Full.MODE_H2), ('l', BH1750Full.MODE_L)):
    check_true('measure_once_' + name, 0 <= sensor.measure_once(mode) <= 120000)
check_true('lux_per_count_default', abs(sensor.lux_per_count() - 1 / 1.2) < 1e-9)
check_true('measurement_time_default', abs(sensor.measurement_time() - 0.180) < 1e-9)
sensor.start_continuous(BH1750Full.MODE_H)
time.sleep(sensor.measurement_time() + 0.02)
check_true('read_continuous_range', 0 <= sensor.read_continuous() <= 120000)
sensor.set_measurement_time(138)
check_true('mtreg_roundtrip', sensor.get_measurement_time_register() == 138)
check_true('measurement_time_scaled', abs(sensor.measurement_time() - 0.360) < 1e-9)
check_true('read_with_mtreg', 0 <= sensor.measure_once(BH1750Full.MODE_H) <= 120000)
sensor.set_sensitivity(1.0)
check_true('sensitivity_default', sensor.get_measurement_time_register() == 69)
sensor.power_down()
sensor.power_on()
sensor.reset()
check_true('read_after_reset', 0 <= sensor.measure_once() <= 120000)
sensor.power_down()
connection.close()

print('===DONE: %d passed, %d failed===' % (passed, failed))
