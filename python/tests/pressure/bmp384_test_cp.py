import time
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.pressure.bmp384 import BMP384Minimal, BMP384Full

passed = 0
failed = 0

import busio
i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

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

bmp_full = BMP384Full(connection)
if bmp_full.is_data_ready() in (True, False):
    print('PASS is_data_ready')
    passed += 1
else:
    print('FAIL is_data_ready')
    failed += 1

bmp_full.configure(osr_p=2, osr_t=1, iir_filter=1, odr_sel=0x04)
if bmp_full._osr_p == 2 and bmp_full._iir == 1 and bmp_full._odr == 0x04:
    print('PASS configure_writes_through')
    passed += 1
else:
    print('FAIL configure_writes_through: osr_p={} iir={} odr={}'.format(
        bmp_full._osr_p, bmp_full._iir, bmp_full._odr))
    failed += 1

bmp_full.fifo_configure(press_en=True, temp_en=True, wtm=10)
frames = bmp_full.fifo_read()
if isinstance(frames, list):
    print('PASS fifo_read_returns_list')
    passed += 1
else:
    print('FAIL fifo_read_returns_list: got {}'.format(type(frames)))
    failed += 1

print('===DONE: {} passed, {} failed==='.format(passed, failed))
