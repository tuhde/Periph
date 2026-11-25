"""Minimal example for the TMC2130 — move a stepper with STEP/DIR pulses.

Constructs the driver with the defaults (SPI mode 3, StealthChop below
TPWMTHRS, 16 microsteps), sets a run current, and moves the motor 400 full
steps forward and back. The chip has no internal step generator, so the
driver pulses the STEP pin.
"""

import time
from machine import SPI, Pin
from periph.connection.spi_micropython import SPIConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.motor.tmc2130 import TMC2130Minimal

spi = SPI(1, baudrate=1_000_000, polarity=1, phase=1)    # Create SPI bus, (bus=1, baudrate=1 MHz, mode 3) → SPI
connection = SPIConnection(spi, Pin(5, Pin.OUT))         # Create SPI connection, (spi, cs) → SPIConnection
motor = TMC2130Minimal(connection, MicroPythonOutputPin(6), MicroPythonOutputPin(9), r_sense_ohm=0.11)  # Create TMC2130 driver, (connection, step_pin, dir_pin, r_sense_ohm=0.11 Ω, en_pin=None)

motor.set_current(0.8)                                   # Set run current, (rms_a A RMS) → None
motor.enable()                                           # Power stage on, () → None
for steps in (400, -400):
    motor.move(steps, 200)                               # Move, (full_steps, full_steps_per_s full steps/s) → None
    time.sleep(0.5)
    print('standstill:', motor.is_standstill())          # Read standstill flag, () → bool
    print('fault:', motor.read_fault())                  # Read fault bitmask, () → int

motor.disable()                                          # Power stage off, () → None
