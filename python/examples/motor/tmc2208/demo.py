"""Demo for the TMC2208 — a silent desk-fan / camera-slider controller.

Enables the driver at 0.8 A with 16 microsteps, proves the UART link by
reading IOIN.VERSION and IFCNT, then ramps the internal step generator from
0 to 600 full steps/s in 100 steps/s increments (the chip has no ramp of its
own) printing StealthChop/SpreadCycle and CS_ACTUAL at each step. TPWMTHRS
switches to SpreadCycle above 400 steps/s. After holding speed for 3 s it
ramps down, reverses and repeats once. Any fault stops the motor, disables
the driver and exits.
"""

import time
from machine import UART, Pin
from periph.connection.uart_micropython import UARTConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.motor.tmc2208 import TMC2208Full

uart = UART(1, baudrate=115200, tx=Pin(17), rx=Pin(16), timeout=100)
connection = UARTConnection(uart, 115200)
motor = TMC2208Full(connection, en_pin=MicroPythonOutputPin(4))  # Create TMC2208 Full driver, (connection, r_sense_ohm=0.11 Ω, en_pin)

MICROSTEPS = 16
SPREAD_ABOVE = 400


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


def ramp(start, stop, sign):
    # --- Ramp the internal generator in 100 steps/s increments ---
    # VACTUAL has no acceleration of its own, so the demo steps the speed
    # and reports whether the chip is in StealthChop or SpreadCycle.
    step = 100 if stop >= start else -100
    for speed in range(start, stop + step, step):
        motor.run(sign * speed)                          # Spin with the internal generator, (steps_per_s full steps/s) → None
        time.sleep(0.5)
        status = motor.read_status()                     # Read driver status, () → dict
        print('%4d steps/s  %s  CS=%d' % (
            speed, 'stealth' if status['stealth'] else 'spread', status['cs_actual']))
        guard()


# --- Bring up the link and the power stage ---
# IOIN.VERSION and IFCNT prove the UART wiring and echo handling work
# before any motion is attempted.
ioin = motor.read_ioin()                                 # Read IOIN, () → dict
print('version 0x%02X, IFCNT %d' % (ioin['version'], motor.read_ifcnt()))  # Read write counter, () → int
motor.set_current(0.8)                                   # Set run current, (rms_a A RMS) → None
motor.set_microsteps(MICROSTEPS)                         # Set microstep resolution, (n 1–256) → None
motor.enable()                                           # Power stage on, () → None

# --- Quiet at low speed, strong at high speed ---
# StealthChop is silent but loses torque at speed; TPWMTHRS makes the chip
# change to SpreadCycle once TSTEP drops below the value for 400 steps/s.
tpwmthrs = int(12e6 / (SPREAD_ABOVE * 256))
motor.set_stealthchop_threshold(tpwmthrs)                # Set StealthChop velocity limit, (tpwmthrs 20 bit) → None

for sign in (1, -1):
    ramp(0, 600, sign)
    time.sleep(3)
    ramp(600, 0, sign)
    motor.stop()                                         # Stop the motor, () → None
    time.sleep(1)

# --- Shut down and report ---
# Temperature comparators and GSTAT show whether the run stressed the driver.
print('temperature flags', motor.read_temperature_flags())  # Read temperature flags, () → int 0–15
print('global status', motor.read_global_status())       # Read GSTAT, (clear=False) → dict
motor.disable()                                          # Power stage off, () → None
