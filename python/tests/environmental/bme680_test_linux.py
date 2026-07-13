import os
from periph.transport.i2c_linux import I2CTransport
from periph.chips.environmental.bme680 import BME680Minimal, BME680Full

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x76'), 16)

transport = I2CTransport(I2C_BUS, I2C_ADDR)

bme = BME680Minimal(transport)

t = bme.temperature()
if t >= -40 and t <= 85:
    print('PASS temperature_range')
    passed += 1
else:
    print('FAIL temperature_range: got {}'.format(t))
    failed += 1

p = bme.pressure()
if p >= 300 and p <= 1100:
    print('PASS pressure_range')
    passed += 1
else:
    print('FAIL pressure_range: got {}'.format(p))
    failed += 1

h = bme.humidity()
if h >= 0 and h <= 100:
    print('PASS humidity_range')
    passed += 1
else:
    print('FAIL humidity_range: got {}'.format(h))
    failed += 1

bme_full = BME680Full(transport)
if bme_full._osrs_t == 1 and bme_full._osrs_p == 1 and bme_full._osrs_h == 1:
    print('PASS default_oversampling')
    passed += 1
else:
    print('FAIL default_oversampling')
    failed += 1

bme_full.set_oversampling(BME680Full.OSRS_X4, BME680Full.OSRS_X2, BME680Full.OSRS_X1)
if bme_full._osrs_t == 3 and bme_full._osrs_p == 2 and bme_full._osrs_h == 1:
    print('PASS set_oversampling')
    passed += 1
else:
    print('FAIL set_oversampling')
    failed += 1

cid = bme_full.chip_id()
if cid == 0x61:
    print('PASS chip_id')
    passed += 1
else:
    print('FAIL chip_id: expected 0x61, got 0x{:02X}'.format(cid))
    failed += 1

t2, p2, h2, g2 = bme_full.read_all()
if t2 >= -40 and t2 <= 85 and p2 >= 300 and p2 <= 1100 and h2 >= 0 and h2 <= 100:
    print('PASS read_all')
    passed += 1
else:
    print('FAIL read_all: T={} P={} H={}'.format(t2, p2, h2))
    failed += 1

bme_full.reset()
print('PASS reset')
passed += 1

transport.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
