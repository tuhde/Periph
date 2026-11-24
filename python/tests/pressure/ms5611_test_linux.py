import os
import time
from periph.connection.i2c_linux import I2CConnection
from periph.chips.pressure.ms5611 import MS5611Full

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x77'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


sensor = MS5611Full(connection)
sensor.init()
check_true('prom_crc_ok', sensor.prom_crc_ok())
m = sensor.read()
check_true('read_temperature_range', -40 < m['temperature_c'] < 85)
check_true('read_pressure_range', 30000 < m['pressure_pa'] < 120000)
check_true('read_pressure_single', 30000 < sensor.read_pressure() < 120000)
check_true('read_temperature_single', -40 < sensor.read_temperature() < 85)
d1, d2 = sensor.read_raw()
check_true('read_raw_range', 0 < d1 < 16777216 and 0 < d2 < 16777216)
check_true('compensate_matches_read', abs(sensor.compensate(d1, d2)['pressure_pa'] - m['pressure_pa']) < 200)
check_true('coefficients_nonzero', all(0 < c < 65536 for c in sensor.coefficients()))
check_true('serial_code_range', 0 <= sensor.serial_code() < 4096)
check_true('read_prom_length', len(sensor.read_prom()) == 8)
for osr in (256, 512, 1024, 2048, 4096):
    sensor.set_oversampling(osr)
    check_true('osr_%d_roundtrip' % osr, sensor.get_oversampling() == osr)
    check_true('osr_%d_read' % osr, 30000 < sensor.read_pressure() < 120000)
sensor.set_oversampling(4096)
sensor.start_conversion(MS5611Full.TEMPERATURE)
time.sleep(sensor.conversion_time() + 0.002)
check_true('non_blocking_adc', sensor.read_adc() > 0)
check_true('altitude_plausible', -500 < sensor.altitude() < 6000)
sensor.reset()
check_true('read_after_reset', 30000 < sensor.read()['pressure_pa'] < 120000)

print('===DONE: %d passed, %d failed===' % (passed, failed))
connection.close()
