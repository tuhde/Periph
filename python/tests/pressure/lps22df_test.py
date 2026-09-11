import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.pressure.lps22df import LPS22DFMinimal, LPS22DFFull
from machine import Pin

passed = 0
failed = 0

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

lps = LPS22DFMinimal(connection)

t = lps.temperature()
if -40.0 <= t <= 85.0:
    print('PASS temperature_range'); passed += 1
else:
    print('FAIL temperature_range: got {}'.format(t)); failed += 1

p = lps.pressure()
if 26000.0 <= p <= 126000.0:
    print('PASS pressure_range'); passed += 1
else:
    print('FAIL pressure_range: got {}'.format(p)); failed += 1

lps_full = LPS22DFFull(connection)
lps_full.configure(odr=3, avg=0, en_lpfp=True, lfpf_cfg=1, bdu=True)
p2 = lps_full.pressure()
if 26000.0 <= p2 <= 126000.0:
    print('PASS configure_then_read'); passed += 1
else:
    print('FAIL configure_then_read: got {}'.format(p2)); failed += 1

alt = lps_full.altitude(101325.0)
if -500.0 <= alt <= 10000.0:
    print('PASS altitude'); passed += 1
else:
    print('FAIL altitude: got {}'.format(alt)); failed += 1

print('===DONE: {} passed, {} failed==='.format(passed, failed))