"""Linux hardware test for the ADS1114 — runs against real hardware.

Checks single-shot reads, data rate, gain, continuous mode, threshold registers, comparator bit encoding and reset.
"""

import os
import time
from periph.connection.i2c_linux import I2CConnection
from periph.chips.adc_dac.ads1114 import ADS1114Minimal, ADS1114Full, I2C_ADDRESS

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

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', hex(I2C_ADDRESS)), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

sensor = ADS1114Minimal(connection)
check_true('construct_minimal', isinstance(sensor, ADS1114Minimal))
raw = sensor.read_raw()
check_true('read_raw_range', -32768 <= raw <= 32767)
v = sensor.read_voltage()
check_true('read_voltage_matches_raw_scale', abs(v - raw * 0.0000625) < 0.05)

full = ADS1114Full(connection)
check_true('construct_full', isinstance(full, ADS1114Full))
full.set_data_rate(ADS1114Full.DR_860)
check_true('data_rate_roundtrip', full.get_data_rate() == 7 and full.data_rate_sps() == 860)
full.set_gain(ADS1114Full.PGA_4_096)
check_true('gain_roundtrip', full.get_gain() == 1 and full.full_scale() == 4.096)
full.set_gain(ADS1114Full.PGA_2_048)
full.start_continuous()
time.sleep(0.05)
cont = full.read_continuous_raw()
check_true('continuous_range', -32768 <= cont <= 32767)
check_true('continuous_config_mode', not (full.read_config() & 0x0100))
full.stop_continuous()
check_true('single_shot_config_mode', bool(full.read_config() & 0x0100))
full.set_low_threshold_raw(-1000)
full.set_high_threshold_raw(1000)
check_true('threshold_roundtrip', full.get_thresholds_raw() == (-1000, 1000))
full.configure_comparator(ADS1114Full.COMP_WINDOW, ADS1114Full.ACTIVE_LOW, False, ADS1114Full.QUEUE_4)
cfg = full.read_config()
check_true('comparator_bits', (cfg & 0x0017) == 0x0012)
full.disable_comparator()
check_true('comparator_disabled', (full.read_config() & 0x0003) == 0x0003)
full.reset()
check_true('reset_config', (full.read_config() & 0x7FFF) == 0x0583)

print('===DONE: %d passed, %d failed===' % (passed, failed))
