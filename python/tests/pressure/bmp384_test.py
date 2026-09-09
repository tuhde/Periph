import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.pressure.bmp384 import BMP384Minimal
from machine import Pin

passed = 0
failed = 0

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

bmp = BMP384Minimal(connection)

# Validate calibration coefficients were loaded (PAR_T1 must be > 0 for a real chip).
if bmp._par_t1 > 0:
    print('PASS calibration_loaded')
    passed += 1
else:
    print('FAIL calibration_loaded: par_t1 = {}'.format(bmp._par_t1))
    failed += 1

# Verify the default configuration is wired into the driver state.
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

# Read live temperature and pressure from hardware and sanity-check ranges.
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
