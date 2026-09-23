"""Demo for the DRV8830 — a battery-powered toy motor controller.

Holds the motor at a regulated 3.0 V forward, then 2.0 V reverse, printing
the commanded output every second — the DRV8830 keeps that average voltage
constant as the battery sags. Brakes, then coasts. After every drive() call
the fault register is checked; a fault (e.g. a stalled motor tripping
ILIMIT) stops the motor and clears the fault before continuing.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.motor.drv8830 import DRV8830Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
motor = DRV8830Full(connection)                          # Create DRV8830 Full driver, (connection)


def check_fault():
    # --- Recover from a fault instead of leaving the bridge latched off ---
    # OCP and ILIMIT disable the H-bridge until CLEAR is written; stop first
    # so the motor does not lurch back to the old command on clear.
    fault, ocp, uvlo, ots, ilimit = motor.read_fault()   # Read fault status, () → (bool, bool, bool, bool, bool)
    if fault:
        names = [n for n, f in (('OCP', ocp), ('UVLO', uvlo), ('OTS', ots), ('ILIMIT', ilimit)) if f]
        print('fault:', ', '.join(names))
        motor.stop()                                     # Coast to standby, () → None
        motor.clear_fault()                              # Clear fault bits, () → None


def run(voltage, seconds):
    # --- Hold a regulated voltage and watch it stay put ---
    # The chip PWM-regulates the bridge against VCC internally, so the
    # commanded voltage (and motor speed) holds while the battery discharges.
    motor.drive(voltage)                                 # Drive at regulated voltage, (voltage V, signed) → None
    check_fault()
    for _ in range(seconds):
        time.sleep(1)
        v, direction = motor.read_output()               # Read back CONTROL, () → (float V, str)
        print('{:7s} {:.2f} V'.format(direction, v))


run(3.0, 5)
run(-2.0, 5)

# --- Stop quickly, then release ---
# Braking shorts the winding for a fast stop; coasting afterwards removes
# the load so the motor does not sit shorted indefinitely.
motor.brake()                                            # Short-brake, () → None
time.sleep(0.5)
motor.stop()                                             # Coast to standby, () → None
