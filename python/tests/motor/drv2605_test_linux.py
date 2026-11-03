"""Linux hardware test for the DRV2605 — runs against real hardware.

Initialises the chip, checks the device id, plays a ROM effect and a
sequence, streams an RTP level, runs diagnostics and reads the status.
"""

import time
import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.motor.drv2605 import DRV2605Minimal, DRV2605Full, I2C_ADDRESS

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

haptic = DRV2605Minimal(connection)
check_true('construct_minimal', isinstance(haptic, DRV2605Minimal))
haptic.play_effect(1)
time.sleep(0.5)
check_true('effect_finishes', not haptic.is_playing())

full = DRV2605Full(connection)
check_true('device_id', full.read_device_id() == 3)
full.play_sequence([1, ('wait', 100), 1])
check_true('sequence_finishes', full.wait_done(3000))

full.rtp_level(0.5)
time.sleep(0.1)
check_true('supply_voltage_plausible', 2.0 < full.read_supply_voltage() < 6.0)
full.stop()
check_true('stop_clears_go', not full.is_playing())

check_true('diagnose_runs', full.diagnose() in (True, False))
status = full.read_status()
check_true('no_overtemp', not status[2])
check_true('no_overcurrent', not status[3])

print('===DONE: %d passed, %d failed===' % (passed, failed))
