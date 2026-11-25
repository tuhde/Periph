"""Linux hardware test for the ADS1014 — runs against a Raspberry Pi.

Checks the single-shot read, gain/data-rate/threshold round-trips, continuous mode,
comparator Config bits and the conversion-ready routing.
The input only needs to be a valid voltage; no particular level is asserted.
"""

import time
import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.adc_dac.ads1014 import (
    I2C_ADDRESS, ADS1014Minimal, ADS1014Full, PGA_2_048, PGA_0_256, DR_250, DR_1600,
    COMP_WINDOW, ACTIVE_HIGH, QUEUE_2, SOURCE_CONVERSION_READY)

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
adc = ADS1014Minimal(connection)
check_true('construct_minimal', isinstance(adc, ADS1014Minimal))
raw = adc.read_raw()
check_true('raw_in_range', -2048 <= raw <= 2047)
check_true('voltage_matches_raw', abs(adc.read_voltage() - 0.001 * raw) < 0.05)

full = ADS1014Full(connection)
full.reset()
check_true('reset_config', full.read_config() & 0x7FFF == 0x0583)

full.set_gain(PGA_0_256)
check_true('gain_roundtrip', full.get_gain() == PGA_0_256 and full.full_scale() == 0.256)
full.read_raw()
check_true('config_gain_bits', (full.read_config() >> 9) & 0x7 == PGA_0_256)
full.set_gain(PGA_2_048)

full.set_data_rate(DR_250)
check_true('dr_roundtrip', full.get_data_rate() == DR_250 and full.data_rate_sps() == 250)
full.read_raw()
check_true('config_dr_bits', (full.read_config() >> 5) & 0x7 == DR_250)
full.set_data_rate(DR_1600)

full.start_continuous()
time.sleep(0.05)
check_true('continuous_raw_in_range', -2048 <= full.read_continuous_raw() <= 2047)
check_true('continuous_mode_bit', full.read_config() & 0x0100 == 0)
full.stop_continuous()
check_true('stop_mode_bit', full.read_config() & 0x0100 != 0)

full.set_low_threshold_raw(-100)
full.set_high_threshold_raw(300)
check_true('threshold_roundtrip', full.get_thresholds_raw() == (-100, 300))
full.set_thresholds(-0.2, 0.5)
check_true('threshold_volts_roundtrip', full.get_thresholds_raw() == (-200, 500))

full.configure_comparator(COMP_WINDOW, ACTIVE_HIGH, True, QUEUE_2)
cfg_reg = full.read_config()
check_true('comparator_bits', cfg_reg & 0x1F == (0x10 | 0x08 | 0x04 | 0x01))
full.clear_alert()
full.disable_comparator()
check_true('comparator_disabled', full.read_config() & 0x3 == 3)

full.enable_interrupt(SOURCE_CONVERSION_READY)
check_true('ready_thresholds', full.get_thresholds_raw() == (0, -2048))
check_true('ready_que_00', full.read_config() & 0x3 == 0)
full.disable_interrupt(SOURCE_CONVERSION_READY)
full.reset()
check_true('final_reset', full.read_config() & 0x7FFF == 0x0583)

print('===DONE: %d passed, %d failed===' % (passed, failed))
