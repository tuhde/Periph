"""Minimal example for the MMA8451Q — read X, Y, Z acceleration in a loop.

Constructs the driver with the defaults (±2 g, 100 Hz, 14-bit output,
normal oversampling) and prints each sample to stdout.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.mma8451q import MMA8451QMinimal

# I²C bus on most ESP32 / Pi Pico dev boards; adjust for your wiring.
# 0x1C = SA0 tied to GND, 0x1D = SA0 tied to VDDIO.
i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x1C)
accel = MMA8451QMinimal(connection)                        # Create MMA8451Q driver, (connection)

for _ in range(10):
    x, y, z = accel.read()                                 # Read 3-axis acceleration, () → tuple(float, float, float) g
    print('x={:.3f} y={:.3f} z={:.3f} g'.format(x, y, z))
