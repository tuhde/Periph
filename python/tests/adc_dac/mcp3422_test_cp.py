"""CircuitPython hardware test for the MCP3422 — runs against real hardware.

Reads one-shot conversions on both channels, changes resolution and gain, runs
continuous conversion and checks the configuration byte. Needs an input signal
within the full-scale range (inputs may be shorted or tied together).
"""

import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.adc_dac.mcp3422 import MCP3422Minimal, MCP3422Full

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
# Defer I2C lock acquisition to the try/finally so we always release it.
i2c.try_lock()
i2c.unlock()
connection = I2CConnection(i2c, cfg.ADDR)

adc = MCP3422Minimal(connection)
check_true('construct_minimal', isinstance(adc, MCP3422Minimal))
raw = adc.read_raw(0)
check_true('raw_in_16_bit_range', -32768 <= raw <= 32767)
volts = adc.read_voltage(1)
check_true('voltage_in_range', -2.048 <= volts <= 2.048)
check_true('read_all_two_values', len(adc.read_all()) == 2)

full = MCP3422Full(connection)
full.set_resolution(12)
full.set_gain(2)
check_true('selection_roundtrip', full.get_resolution() == 12 and full.get_gain() == 2)
check_true('full_scale', abs(full.full_scale() - 1.024) < 1e-9)
check_true('one_shot_12_bit_range', -2048 <= full.read_raw(0) <= 2047)
full.set_gain(1)
full.start_continuous(0)
check_true('continuous_flag', full.is_continuous())
check_true('continuous_sample_in_range', -2048 <= full.read_continuous_raw() <= 2047)
check_true('read_config_rdy_bit', full.read_config() & 0x10)
full.stop_continuous()
check_true('stopped', not full.is_continuous())
check_true('read_scan_length', len(full.read_scan([0, 1])) == 2)

print('===DONE: %d passed, %d failed===' % (passed, failed))
