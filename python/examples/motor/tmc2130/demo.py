"""Demo for the TMC2130 — sensorless homing of a linear axis with StallGuard2.

Enables the driver at 0.8 A with 16 microsteps, proves the SPI link by
reading IOIN.VERSION (0x11), switches to SpreadCycle (StallGuard2 does not
work in StealthChop) and drives toward the end stop in bursts of 20 full
steps at 150 full steps/s. When SG_RESULT reaches 0 the demo stops, prints
the SG_RESULT history, backs off 200 full steps and finishes with a CoolStep
move. Any fault stops the motor, disables the driver and exits.
"""

import time
from machine import SPI, Pin
from periph.connection.spi_micropython import SPIConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.motor.tmc2130 import TMC2130Full

spi = SPI(1, baudrate=1_000_000, polarity=1, phase=1)
connection = SPIConnection(spi, Pin(5, Pin.OUT))
motor = TMC2130Full(connection, MicroPythonOutputPin(6), MicroPythonOutputPin(9),
                    en_pin=MicroPythonOutputPin(4))      # Create TMC2130 Full driver, (connection, step_pin, dir_pin, r_sense_ohm=0.11 Ω, en_pin)

HOMING_SPEED = 150
BURST = 20


def guard():
    # --- Stop on any driver fault ---
    # Short circuits and over-temperature latch until the driver is disabled,
    # so the demo shuts everything down and reports which fault fired.
    fault = motor.read_fault()                           # Read fault bitmask, () → int
    if fault:
        motor.disable()                                  # Power stage off, () → None
        print('fault 0x%02X' % fault)
        raise SystemExit


# --- Bring up the link and the power stage ---
# IOIN.VERSION proves the SPI wiring and Mode 3 setting work before any
# motion is attempted.
ioin = motor.read_ioin()                                 # Read IOIN, () → dict
print('version 0x%02X' % ioin['version'])
motor.set_current(0.8)                                   # Set run current, (rms_a A RMS) → None
motor.set_microsteps(16)                                 # Set microstep resolution, (n 1–256) → None
motor.enable()                                           # Power stage on, () → None

# --- Arm StallGuard2 for the homing speed ---
# StallGuard2 needs SpreadCycle and TSTEP <= TCOOLTHRS, so TCOOLTHRS is set
# just above the TSTEP of the homing speed; sgt = 0 is the usual start value.
motor.set_chopper_mode(True)                             # Choose chopper, (spreadcycle) → None
motor.set_stall_threshold(0)                             # Set StallGuard2 threshold, (sgt -64..63) → None
motor.set_coolstep_threshold(int(12e6 / (HOMING_SPEED * 256 / 2)))  # Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None

# --- Drive toward the end stop in short bursts ---
# The first burst is spin-up, where SG_RESULT is not yet valid; after each
# burst the demo reads SG_RESULT until the stall flag is set.
history = []
motor.move(BURST, HOMING_SPEED)                          # Move, (full_steps, full_steps_per_s full steps/s) → None
while True:
    motor.move(BURST, HOMING_SPEED)                      # Move, (full_steps, full_steps_per_s full steps/s) → None
    sg = motor.read_stallguard()                         # Read StallGuard2 result, () → int 0–1023
    history.append(sg)
    print('SG_RESULT', sg)
    if motor.is_stalled():                               # Read stall flag, () → bool
        break
    guard()

# --- End stop found: report and back off ---
# The last few SG_RESULT values show how the load built up before the stall.
print('history', history[-5:])
motor.move(-200, HOMING_SPEED)                           # Move, (full_steps, full_steps_per_s full steps/s) → None

# --- Final move with CoolStep ---
# CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
motor.configure_coolstep(5, 2, 1, 1, 0)                  # Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → None
for _ in range(10):
    motor.move(20, HOMING_SPEED)                         # Move, (full_steps, full_steps_per_s full steps/s) → None
    print('CS_ACTUAL', motor.read_current_scale())       # Read actual current scale, () → int 0–31
    guard()

# --- Shut down and report ---
# GSTAT shows whether the run stressed the driver; the chip clears it on read.
print('status', motor.read_status())                     # Read driver status, () → dict
print('global status', motor.read_global_status())      # Read GSTAT, () → dict
motor.disable()                                          # Power stage off, () → None
