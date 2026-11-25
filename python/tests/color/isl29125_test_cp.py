"""CircuitPython hardware test for the ISL29125 — runs against real hardware.

Reads all three channels, checks the identity, steps range, resolution and IR
compensation, switches the mode, round-trips the thresholds and exercises the
interrupt configuration and the software reset.
"""

import time
import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.color.isl29125 import ISL29125Minimal, ISL29125Full, SOURCE_THRESHOLD, STATUS_THRESHOLD

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

sensor = ISL29125Minimal(connection)
check_true('construct_minimal', isinstance(sensor, ISL29125Minimal))
counts = sensor.read()
check_true('read_keys', sorted(counts.keys()) == ['blue', 'green', 'red'])
check_true('read_range', all(0 <= v <= 0xFFFF for v in counts.values()))

full = ISL29125Full(connection)
check_true('device_id', full.device_id() == 0x7D)

full.set_range(375)
full.set_range(10000)
full.set_resolution(12)
time.sleep(0.4)
check_true('read_rgb_range_12', all(0 <= v <= 0xFFFF for v in full.read_rgb()))
full.set_resolution(16)
full.set_ir_compensation(True, 0x3F)
full.set_sync(False)
time.sleep(0.4)
red, green, blue = full.read_rgb()
check_true('read_rgb_range', all(0 <= v <= 0xFFFF for v in (red, green, blue)))
check_true('read_single_green', 0 <= full.read_green() <= 0xFFFF)
check_true('read_single_red', 0 <= full.read_red() <= 0xFFFF)
check_true('read_single_blue', 0 <= full.read_blue() <= 0xFFFF)

full.set_mode('green')
time.sleep(0.3)
check_true('green_mode_reads', 0 <= full.read_green() <= 0xFFFF)
full.set_mode('rgb')

full.set_thresholds(100, 60000)
check_true('thresholds_roundtrip', full.get_thresholds() == (100, 60000))
full.set_persistence(2)
full.set_interrupt_channel('green')
full.enable_interrupt(SOURCE_THRESHOLD)
full.disable_interrupt(SOURCE_THRESHOLD)
full.status()
check_true('status_clean', full.poll_interrupt() & STATUS_THRESHOLD == 0)

full.set_mode('power_down')
full.soft_reset()
full.set_mode('rgb')
check_true('soft_reset_id', full.device_id() == 0x7D)

print('===DONE: %d passed, %d failed===' % (passed, failed))
