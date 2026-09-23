"""Complete example for the DRV8830 — exercise every method in the public API.

Constructs the Full driver, drives the motor by voltage and by raw VSET code,
reads back the output state, brakes and coasts, reads and clears the fault
register, and subscribes to FAULTn interrupts.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.motor.drv8830 import DRV8830Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
motor = DRV8830Full(connection)                          # Create DRV8830 Full driver, (connection)

motor.drive(2.5)                                         # Drive at regulated voltage, (voltage V, + = forward) → None
                                                         # maps 2.5 V to the nearest VSET code and sets IN1=1, IN2=0
time.sleep(1)
voltage, direction = motor.read_output()                 # Read back CONTROL, () → (float V, str)
                                                         # decodes VSET to volts and IN1/IN2 to a direction name
print('commanded {:.2f} V {}'.format(voltage, direction))

motor.drive(-1.5)                                        # Drive at regulated voltage, (voltage V, - = reverse) → None
                                                         # a negative voltage sets IN1=0, IN2=1
time.sleep(1)

motor.set_output(37, True, False)                        # Write raw CONTROL fields, (vset 6–63, in1, in2) → None
                                                         # VSET 37 is ~2.97 V forward; codes 0–5 are rejected
time.sleep(1)

motor.brake()                                            # Short-brake, () → None
                                                         # IN1=IN2=1 drives both outputs high
time.sleep(0.5)
motor.stop()                                             # Coast to standby, () → None
                                                         # IN1=IN2=0 leaves both outputs high-impedance

fault, ocp, uvlo, ots, ilimit = motor.read_fault()       # Read fault status, () → (bool, bool, bool, bool, bool)
                                                         # does not clear — latched OCP/ILIMIT keep the bridge off
print('fault={} ocp={} uvlo={} ots={} ilimit={}'.format(fault, ocp, uvlo, ots, ilimit))
motor.clear_fault()                                      # Clear fault bits, () → None
                                                         # writes CLEAR=1; re-enables a latched-off bridge


def on_fault(status):
    print('fault interrupt', status)


motor.on_interrupt(on_fault)                             # Subscribe to FAULTn, (callback, int_pin=None) → None
                                                         # callback receives the read_fault() tuple
status = motor.poll_interrupt()                          # Poll fault status, () → (bool, bool, bool, bool, bool)
                                                         # same as read_fault(); never clears implicitly
motor.off_interrupt()                                    # Unsubscribe, () → None
print('poll', status)
