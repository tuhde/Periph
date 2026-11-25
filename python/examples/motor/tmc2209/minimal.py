"""Minimal example for the TMC2209 — spin a stepper with the internal step generator.

Constructs the driver with the defaults (UART mode, StealthChop, 16
microsteps), sets a run current, and turns the motor in both directions
without any STEP pulses.
"""

import time
from machine import UART, Pin
from periph.connection.uart_micropython import UARTConnection
from periph.chips.motor.tmc2209 import TMC2209Minimal

uart = UART(1, baudrate=115200, tx=Pin(17), rx=Pin(16), timeout=100)
connection = UARTConnection(uart, 115200)
motor = TMC2209Minimal(connection, r_sense_ohm=0.11)     # Create TMC2209 driver, (connection, r_sense_ohm=0.11 Ω, address=0, en_pin=None, echo=True)

motor.set_current(0.8)                                   # Set run current, (rms_a A RMS) → None
for speed in (200, -200):
    motor.run(speed)                                     # Spin with the internal generator, (steps_per_s full steps/s, sign = direction) → None
    time.sleep(3)
    print('standstill:', motor.is_standstill())         # Read standstill flag, () → bool
    print('fault:', motor.read_fault())                  # Read fault bitmask, () → int

motor.stop()                                             # Stop the motor, () → None
