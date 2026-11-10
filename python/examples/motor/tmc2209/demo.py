"""Demo for the TMC2209 — sensorless homing of a linear axis with StallGuard4.

Enables the driver at 0.8 A with 16 microsteps, proves the UART link by
reading IOIN.VERSION (0x21) and IFCNT, then drives toward the end stop with
the internal step generator at 200 full steps/s. When SG_RESULT falls to
2 * SGTHRS the axis has hit the stop: the demo stops, reverses for 2 s to
back off and finishes with a CoolStep move. Any fault stops the motor,
disables the driver and exits.
"""

import time
from machine import UART, Pin
from periph.connection.uart_micropython import UARTConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.motor.tmc2209 import TMC2209Full

uart = UART(1, baudrate=115200, tx=Pin(17), rx=Pin(16), timeout=100)
connection = UARTConnection(uart, 115200)
motor = TMC2209Full(connection, en_pin=MicroPythonOutputPin(4))  # Create TMC2209 Full driver, (connection, r_sense_ohm=0.11 Ω, address=0, en_pin)

MICROSTEPS = 16
HOMING_SPEED = 200
SGTHRS = 60


def guard():
    # --- Stop on any driver fault ---
    # Short circuits and over-temperature latch until the driver is disabled,
    # so the demo shuts everything down and reports which fault fired.
    fault = motor.read_fault()                           # Read fault bitmask, () → int
    if fault:
        motor.stop()                                     # Stop the motor, () → None
        motor.disable()                                  # Power stage off, () → None
        print('fault 0x%02X' % fault)
        raise SystemExit


# --- Bring up the link and the power stage ---
# IOIN.VERSION and IFCNT prove the UART wiring and echo handling work
# before any motion is attempted.
ioin = motor.read_ioin()                                 # Read IOIN, () → dict
print('version 0x%02X, IFCNT %d' % (ioin['version'], motor.read_ifcnt()))  # Read write counter, () → int
motor.set_current(0.8)                                   # Set run current, (rms_a A RMS) → None
motor.set_microsteps(MICROSTEPS)                         # Set microstep resolution, (n 1–256) → None
motor.enable()                                           # Power stage on, () → None

# --- Arm StallGuard4 for the homing speed ---
# StallGuard needs StealthChop and TCOOLTHRS >= TSTEP > TPWMTHRS; TCOOLTHRS
# is set just below the TSTEP of the homing speed so detection is active.
tcoolthrs = int(12e6 / (HOMING_SPEED * 256 / 2))
motor.set_stall_threshold(SGTHRS)                        # Set StallGuard4 threshold, (sgthrs 0–255) → None
motor.set_coolstep_threshold(tcoolthrs)                  # Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None

# --- Drive toward the end stop ---
# The first 0.5 s is spin-up, where SG_RESULT is not yet valid; afterwards
# the demo polls SG_RESULT every 100 ms until it drops to 2 * SGTHRS.
motor.run(HOMING_SPEED)                                  # Spin with the internal generator, (steps_per_s full steps/s) → None
time.sleep(0.5)
while True:
    sg = motor.read_stallguard()                         # Read StallGuard4 result, () → int 0–510
    print('SG_RESULT', sg)
    if sg <= 2 * SGTHRS:
        break
    guard()
    time.sleep(0.1)

# --- End stop found: stop and back off ---
# Reversing for 2 s moves the axis clear of the stop.
motor.stop()                                             # Stop the motor, () → None
print('stalled at SG_RESULT', sg)
motor.run(-HOMING_SPEED)                                 # Spin with the internal generator, (steps_per_s full steps/s) → None
time.sleep(2)
motor.stop()                                             # Stop the motor, () → None

# --- Final move with CoolStep ---
# CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
motor.configure_coolstep(5, 2, 1, 1, 0)                  # Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → None
motor.run(HOMING_SPEED)                                  # Spin with the internal generator, (steps_per_s full steps/s) → None
for _ in range(10):
    time.sleep(0.5)
    print('CS_ACTUAL', motor.read_current_scale())       # Read actual current scale, () → int 0–31
    guard()
motor.stop()                                             # Stop the motor, () → None

# --- Shut down and report ---
# Temperature comparators and GSTAT show whether the run stressed the driver.
print('temperature flags', motor.read_temperature_flags())  # Read temperature flags, () → int 0–15
print('global status', motor.read_global_status())       # Read GSTAT, (clear=False) → dict
motor.disable()                                          # Power stage off, () → None
