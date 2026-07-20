import os
from periph.transport.i2c_linux import I2CTransport
from periph.chips.environmental.bme280 import BME280Minimal, BME280Full

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x76'), 16)

transport = I2CTransport(I2C_BUS, I2C_ADDR)

bme = BME280Minimal(transport)

bme._dig_T1 = 27504
bme._dig_T2 = 26435
bme._dig_T3 = -1000
bme._dig_P1 = 36477
bme._dig_P2 = -10685
bme._dig_P3 = 3024
bme._dig_P4 = 2855
bme._dig_P5 = 140
bme._dig_P6 = -7
bme._dig_P7 = 15500
bme._dig_P8 = -14600
bme._dig_P9 = 6000

t = bme._compensate_temp(519888)
if abs(t - 25.08) < 0.1:
    print('PASS temperature_compensation')
    passed += 1
else:
    print('FAIL temperature_compensation: expected 25.08, got {}'.format(t))
    failed += 1

p = bme._compensate_pressure(415148)
if abs(p - 1006.53) < 0.5:
    print('PASS pressure_compensation')
    passed += 1
else:
    print('FAIL pressure_compensation: expected 1006.53, got {}'.format(p))
    failed += 1

bme._dig_H1 = 75
bme._dig_H2 = 362
bme._dig_H3 = 0
bme._dig_H4 = 341
bme._dig_H5 = 50
bme._dig_H6 = 30
bme._t_fine = 128422

h = bme._compensate_humidity(29000)
if 30.0 <= h <= 70.0:
    print('PASS humidity_compensation')
    passed += 1
else:
    print('FAIL humidity_compensation: expected 30-70 %RH, got {}'.format(h))
    failed += 1

bme_full = BME280Full(transport)
bme_full._mode = 0
if bme_full._osrs_t == 1 and bme_full._osrs_p == 1 and bme_full._osrs_h == 1:
    print('PASS default_oversampling')
    passed += 1
else:
    print('FAIL default_oversampling: got osrs_t={} osrs_p={} osrs_h={}'.format(
        bme_full._osrs_t, bme_full._osrs_p, bme_full._osrs_h))
    failed += 1

bme_full.set_oversampling(BME280Full.OSRS_X4, BME280Full.OSRS_X2, BME280Full.OSRS_X1)
if bme_full._osrs_t == 3 and bme_full._osrs_p == 2 and bme_full._osrs_h == 1:
    print('PASS set_oversampling')
    passed += 1
else:
    print('FAIL set_oversampling: got osrs_t={} osrs_p={} osrs_h={}'.format(
        bme_full._osrs_t, bme_full._osrs_p, bme_full._osrs_h))
    failed += 1

alt = bme_full.altitude(1013.25)
if alt >= -500 and alt <= 9000:
    print('PASS altitude')
    passed += 1
else:
    print('FAIL altitude: expected valid range, got {}'.format(alt))
    failed += 1

slp = bme_full.sea_level_pressure(0)
if slp >= 900 and slp <= 1100:
    print('PASS sea_level_pressure')
    passed += 1
else:
    print('FAIL sea_level_pressure: expected ~1013, got {}'.format(slp))
    failed += 1

transport.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
