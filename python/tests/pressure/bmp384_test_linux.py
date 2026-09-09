import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.pressure.bmp384 import BMP384Minimal

passed = 0
failed = 0

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x76'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

bmp = BMP384Minimal(connection)

if bmp._par_t1 > 0:
    print('PASS calibration_loaded')
    passed += 1
else:
    print('FAIL calibration_loaded: par_t1 = {}'.format(bmp._par_t1))
    failed += 1

if bmp._osr_p == 4 and bmp._osr_t == 1:
    print('PASS default_oversampling')
    passed += 1
else:
    print('FAIL default_oversampling: got osr_p={} osr_t={}'.format(bmp._osr_p, bmp._osr_t))
    failed += 1

if bmp._iir == 2:
    print('PASS default_iir')
    passed += 1
else:
    print('FAIL default_iir: got {}'.format(bmp._iir))
    failed += 1

t = bmp.temperature()
if -40.0 <= t <= 85.0:
    print('PASS temperature_in_range')
    passed += 1
else:
    print('FAIL temperature_in_range: got {}'.format(t))
    failed += 1

p = bmp.pressure()
if 300.0 <= p <= 1250.0:
    print('PASS pressure_in_range')
    passed += 1
else:
    print('FAIL pressure_in_range: got {}'.format(p))
    failed += 1

print('===DONE: {} passed, {} failed==='.format(passed, failed))
connection.close()
