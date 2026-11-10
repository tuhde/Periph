"""Demo for the TMC5160 — sensorless homing and positioning of a linear axis.

Enables the driver at 1.5 A, proves the link via IOIN.VERSION (0x30) and
SD_MODE, switches to SpreadCycle (StallGuard2 does not work in StealthChop),
arms the automatic stall stop and runs toward the end stop at 150 full
steps/s. The motion controller stops the motor itself on the stall; the demo
then defines zero, backs off, programs a two-phase ramp, makes three timed
positioning moves and enables CoolStep for the last one. Any fault stops the
motor, disables the driver and exits.
"""

import time
from machine import SPI, Pin
from periph.connection.spi_micropython import SPIConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.motor.tmc5160 import TMC5160Full

spi = SPI(1, baudrate=1_000_000, polarity=1, phase=1)
connection = SPIConnection(spi, Pin(5, Pin.OUT))
motor = TMC5160Full(connection, en_pin=MicroPythonOutputPin(4))  # Create TMC5160 Full driver, (connection, bus_type='spi', r_sense_ohm=0.075 Ω, en_pin)

HOMING_SPEED = 150


def guard():
    # --- Stop on any driver fault ---
    # Short circuits and over-temperature latch until the driver is disabled,
    # so the demo shuts everything down and reports which fault fired.
    fault = motor.read_fault()                           # Read fault bitmask, () → int
    if fault:
        motor.stop()                                     # Controlled stop, () → None
        motor.disable()                                  # Power stage off, () → None
        print('fault 0x%02X' % fault)
        raise SystemExit


# --- Bring up the link and the power stage ---
# IOIN.VERSION and SD_MODE prove the wiring and the strap setting before any
# motion is attempted.
ioin = motor.read_ioin()                                 # Read IOIN, () → dict
print('version 0x%02X, SD_MODE %d' % (ioin['version'], ioin['sd_mode']))
motor.set_current(1.5)                                   # Set run current, (rms_a A RMS) → None
motor.enable()                                           # Power stage on, () → None

# --- Arm the automatic stall stop ---
# StallGuard2 needs SpreadCycle and TSTEP <= TCOOLTHRS; TCOOLTHRS is set just
# above the TSTEP of the homing speed. sg_stop makes the chip stop by itself.
motor.set_chopper_mode(True)                             # Choose chopper, (spreadcycle) → None
motor.set_stall_threshold(0)                             # Set StallGuard2 threshold, (sgt -64..63) → None
motor.set_coolstep_threshold(int(12e6 / (HOMING_SPEED * 256 / 2)))  # Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None
motor.set_acceleration(600)                              # Set acceleration, (full_steps_per_s2) → None

# --- Run toward the end stop ---
# The first 0.5 s is spin-up, where stall detection is not yet valid; then
# sg_stop is enabled and RAMP_STAT.event_stop_sg tells when the chip has
# stopped the motor.
motor.run(-HOMING_SPEED)                                 # Velocity mode, (full_steps_per_s) → None
time.sleep(0.5)
motor.enable_stall_stop(True)                            # Automatic stop on stall, (enabled) → None
while not motor.read_ramp_status()['event_stop_sg']:     # Read RAMP_STAT, () → dict
    print('SG_RESULT', motor.read_stallguard())          # Read StallGuard2 result, () → int 0–1023
    guard()
    time.sleep(0.1)

# --- End stop found: define zero and back off ---
# The motion controller has already stopped the motor; clear the event,
# release sg_stop and move 20 full steps away while printing the ramp.
motor.clear_ramp_events()                                # Clear event flags, () → None
motor.enable_stall_stop(False)                           # Automatic stop on stall, (enabled) → None
motor.set_position(0)                                    # Define the position, (position full steps) → None
motor.set_speed(300)                                     # Set target speed, (full_steps_per_s) → None
motor.move_to(20)                                        # Positioning move, (position full steps) → None
while not motor.is_position_reached():                   # Position reached flag, () → bool
    print(motor.position(), motor.velocity(), motor.read_stallguard())  # position (float), velocity (float), SG_RESULT (int)
    time.sleep(0.05)

# --- Two-phase ramp, three timed moves ---
# A1/D1 apply below V1 = 200 full steps/s, AMAX/DMAX above it.
motor.configure_ramp(0, 1200, 200, 600, 500, 900, 1500, 10)  # Configure the SixPoint ramp, (vstart, a1, v1, amax, vmax, dmax, d1, vstop) → None
motor.set_chopper_mode(False)                            # Choose chopper, (spreadcycle) → None
for i in range(3):
    if i == 2:
        motor.configure_coolstep(5, 2, 1, 1, 0)          # Configure CoolStep, (semin, semax, seup, sedn, seimin) → None
    motor.move_to(420 if i % 2 == 0 else 20)             # Positioning move, (position full steps) → None
    motor.wait_until_reached()                           # Wait for the target, (timeout_s=None) → bool
    print('move', i, 'done, CS_ACTUAL', motor.read_current_scale())  # Read actual current scale, () → int 0–31
    guard()

# --- Shut down and report ---
# GSTAT shows whether the run stressed the driver.
print('status', motor.read_status())                     # Read driver status, () → dict
print('global status', motor.read_global_status())       # Read GSTAT, () → dict
motor.disable()                                          # Power stage off, () → None
