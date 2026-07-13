import busio
import time
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.pressure.bmp280 import BMP280Minimal, BMP280Full

passed = 0
failed = 0

i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)

bmp = BMP280Minimal(transport)

bmp._dig_T1 = 27504
bmp._dig_T2 = 26435
bmp._dig_T3 = -1000
bmp._dig_P1 = 36477
bmp._dig_P2 = -10685
bmp._dig_P3 = 3024
bmp._dig_P4 = 2855
bmp._dig_P5 = 140
bmp._dig_P6 = -7
bmp._dig_P7 = 15500
bmp._dig_P8 = -14600
bmp._dig_P9 = 6000

t = bmp._compensate_temp(519888)
if abs(t - 25.08) < 0.1:
    print('PASS temperature_compensation')
    passed += 1
else:
    print('FAIL temperature_compensation: expected 25.08, got {}'.format(t))
    failed += 1

p = bmp._compensate_pressure(415148)
if abs(p - 1006.53) < 0.5:
    print('PASS pressure_compensation')
    passed += 1
else:
    print('FAIL pressure_compensation: expected 1006.53, got {}'.format(p))
    failed += 1

bmp_full = BMP280Full(transport)
bmp_full._mode = 0
if bmp_full._osrs_t == 1 and bmp_full._osrs_p == 1:
    print('PASS default_oversampling')
    passed += 1
else:
    print('FAIL default_oversampling: got osrs_t={} osrs_p={}'.format(bmp_full._osrs_t, bmp_full._osrs_p))
    failed += 1

bmp_full.set_oversampling(BMP280Full.OSRS_X4, BMP280Full.OSRS_X2)
if bmp_full._osrs_t == 3 and bmp_full._osrs_p == 2:
    print('PASS set_oversampling')
    passed += 1
else:
    print('FAIL set_oversampling: got osrs_t={} osrs_p={}'.format(bmp_full._osrs_t, bmp_full._osrs_p))
    failed += 1

alt = bmp_full.altitude(1013.25)
if alt >= -500 and alt <= 9000:
    print('PASS altitude')
    passed += 1
else:
    print('FAIL altitude: expected valid range, got {}'.format(alt))
    failed += 1

slp = bmp_full.sea_level_pressure(0)
if slp >= 900 and slp <= 1100:
    print('PASS sea_level_pressure')
    passed += 1
else:
    print('FAIL sea_level_pressure: expected ~1013, got {}'.format(slp))
    failed += 1

print('===DONE: {} passed, {} failed==='.format(passed, failed))
