"""CircuitPython hardware test for the ADS1113 — runs against real hardware.

Checks single-shot reads, data rate, continuous mode and reset.
"""

import time
import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.adc_dac.ads1113 import ADS1113Minimal, ADS1113Full

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1

i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

sensor = ADS1113Minimal(connection)
check_true('construct_minimal', isinstance(sensor, ADS1113Minimal))
raw = sensor.read_raw()
check_true('read_raw_range', -32768 <= raw <= 32767)
v = sensor.read_voltage()
check_true('read_voltage_matches_raw_scale', abs(v - raw * 0.0000625) < 0.05)

full = ADS1113Full(connection)
check_true('construct_full', isinstance(full, ADS1113Full))
full.set_data_rate(ADS1113Full.DR_860)
check_true('data_rate_roundtrip', full.get_data_rate() == 7 and full.data_rate_sps() == 860)
full.start_continuous()
time.sleep(0.05)
cont = full.read_continuous_raw()
check_true('continuous_range', -32768 <= cont <= 32767)
check_true('continuous_config_mode', not (full.read_config() & 0x0100))
full.stop_continuous()
check_true('single_shot_config_mode', bool(full.read_config() & 0x0100))
full.reset()
check_true('reset_config', (full.read_config() & 0x7FFF) == 0x0583)

print('===DONE: %d passed, %d failed===' % (passed, failed))
