import os
from periph.transport.i2c_linux import I2CTransport
from periph.chips.pressure.bmp180 import BMP180Minimal, BMP180Full

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
transport = I2CTransport(I2C_BUS, 0x77)

bmp = BMP180Minimal(transport)

ut = 27898
up = 23843
bmp._oss = 0
bmp._b5 = 0
bmp._ac1 = 408
bmp._ac2 = -72
bmp._ac3 = -14383
bmp._ac4 = 32741
bmp._ac5 = 32757
bmp._ac6 = 23153
bmp._b1 = 6190
bmp._b2 = 4
bmp._mc = -8711
bmp._md = 2868

t = bmp._compensate_temp(ut)
p = bmp._compensate_pressure(up)
if abs(t - 15.0) < 0.1:
    print('PASS temperature_compensation')
    passed += 1
else:
    print('FAIL temperature_compensation: expected 15.0, got {}'.format(t))
    failed += 1

if abs(p - 699.64) < 0.1:
    print('PASS pressure_compensation')
    passed += 1
else:
    print('FAIL pressure_compensation: expected 699.64, got {}'.format(p))
    failed += 1

bmp_full = BMP180Full(transport, oss=0)
if bmp_full.oversampling() == 0:
    print('PASS default_oss')
    passed += 1
else:
    print('FAIL default_oss: expected 0, got {}'.format(bmp_full.oversampling()))
    failed += 1

bmp_full.set_oversampling(2)
if bmp_full.oversampling() == 2:
    print('PASS set_oversampling')
    passed += 1
else:
    print('FAIL set_oversampling: expected 2, got {}'.format(bmp_full.oversampling()))
    failed += 1

alt = bmp_full.altitude(1013.25)
if alt >= 0:
    print('PASS altitude')
    passed += 1
else:
    print('FAIL altitude: expected >= 0, got {}'.format(alt))
    failed += 1

slp = bmp_full.sea_level_pressure(0)
if slp >= 900 and slp <= 1100:
    print('PASS sea_level_pressure')
    passed += 1
else:
    print('FAIL sea_level_pressure: expected ~1013, got {}'.format(slp))
    failed += 1

transport.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
