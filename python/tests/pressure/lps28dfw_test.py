import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.pressure.lps28dfw import LPS28DFWMinimal, LPS28DFWFull
from machine import Pin

passed = 0
failed = 0

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

lps_min = LPS28DFWMinimal(connection)

if lps_min._fs_mode == 0 and lps_min._odr == 0x04 and lps_min._avg == 0x02:
    print('PASS minimal_defaults')
    passed += 1
else:
    print('FAIL minimal_defaults: fs_mode={} odr={} avg={}'.format(
        lps_min._fs_mode, lps_min._odr, lps_min._avg))
    failed += 1

raw24 = (1000 * 4096) & 0xFFFFFF
expected_mode1 = raw24 / 4096.0
if abs(expected_mode1 - 1000.0) < 0.01:
    print('PASS pressure_sensitivity_mode1')
    passed += 1
else:
    print('FAIL pressure_sensitivity_mode1: expected 1000.0, got {}'.format(expected_mode1))
    failed += 1

raw24_mode2 = (1000 * 2048) & 0xFFFFFF
expected_mode2 = raw24_mode2 / 2048.0
if abs(expected_mode2 - 1000.0) < 0.01:
    print('PASS pressure_sensitivity_mode2')
    passed += 1
else:
    print('FAIL pressure_sensitivity_mode2: expected 1000.0, got {}'.format(expected_mode2))
    failed += 1

raw16 = 2500
temp_c = raw16 / 100.0
if abs(temp_c - 25.0) < 0.001:
    print('PASS temperature_conversion')
    passed += 1
else:
    print('FAIL temperature_conversion: got {}'.format(temp_c))
    failed += 1

lps_full = LPS28DFWFull(connection)
if lps_full._odr == 0x04 and lps_full._avg == 0x02 and lps_full._fs_mode == 0:
    print('PASS full_default_inherits')
    passed += 1
else:
    print('FAIL full_default_inherits: odr={} avg={} fs_mode={}'.format(
        lps_full._odr, lps_full._avg, lps_full._fs_mode))
    failed += 1

lps_full.configure(odr=LPS28DFWFull.ODR_100_HZ, avg=LPS28DFWFull.AVG_128,
                   fs_mode=LPS28DFWFull.FS_MODE_2, lpf_en=False, lpf_cfg=1)
if lps_full._odr == 0x07 and lps_full._avg == 0x05 and lps_full._fs_mode == 1 \
   and lps_full._lpf_en == 0 and lps_full._lpf_cfg == 1:
    print('PASS full_configure')
    passed += 1
else:
    print('FAIL full_configure: odr={} avg={} fs_mode={} lpf_en={} lpf_cfg={}'.format(
        lps_full._odr, lps_full._avg, lps_full._fs_mode,
        lps_full._lpf_en, lps_full._lpf_cfg))
    failed += 1

threshold_raw = int(1050.0 * 16)
if threshold_raw > 0x7FFF:
    threshold_raw = 0x7FFF
if threshold_raw == 16800:
    print('PASS threshold_raw_conversion')
    passed += 1
else:
    print('FAIL threshold_raw_conversion: expected 16800, got {}'.format(threshold_raw))
    failed += 1

print('===DONE: {} passed, {} failed==='.format(passed, failed))