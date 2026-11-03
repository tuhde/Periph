"""MicroPython hardware test for the DRV2605 — runs against real hardware.

Initialises the chip, checks the device id, plays a ROM effect and a
sequence, streams an RTP level, runs diagnostics and reads the status.
"""

import time
import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from machine import Pin
from periph.chips.motor.drv2605 import DRV2605Minimal, DRV2605Full

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


i2c = machine.I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

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
