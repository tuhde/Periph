"""Demo for the ADXL345 — stationary tilt sensing for 50 samples at 10 Hz.

Place the ADXL345 flat on a table (Z axis up). The script samples at 10 Hz,
prints x, y, z, and the magnitude √(x²+y²+z²) for each sample, and after
50 samples reports the minimum and maximum magnitude seen. A stationary
sensor should report a magnitude very close to 1 *g* throughout, with x
and y near zero and z near +1 *g*. Tilting the board changes the static
gravity component between axes and demonstrates full-resolution output
tracking orientation continuously.
"""

import math
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.adxl345 import ADXL345Minimal

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x53)
accel = ADXL345Minimal(connection)                      # Create ADXL345 driver, (connection, bus_type='i2c')

# --- 50-sample stationary tilt characterization at 10 Hz ---
# With the sensor flat and the Z axis up, gravity should project entirely
# onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
# across X and Y; the total vector magnitude stays near 1 *g*.
SAMPLES = 50
PERIOD_MS = 100

mag_min = float('inf')
mag_max = float('-inf')

for n in range(SAMPLES):
    x, y, z = accel.read()                             # Read 3-axis acceleration, () → tuple(float, float, float) g
    mag = math.sqrt(x * x + y * y + z * z)
    if mag < mag_min:
        mag_min = mag
    if mag > mag_max:
        mag_max = mag
    print('{:2d}  x={:+.3f}  y={:+.3f}  z={:+.3f}  |a|={:.3f} g'.format(n, x, y, z, mag))
    # Use time.sleep_ms if available; otherwise fall back to time.sleep.
    try:
        import time
        if hasattr(time, 'sleep_ms'):
            time.sleep_ms(PERIOD_MS)
        else:
            time.sleep(PERIOD_MS / 1000.0)
    except ImportError:
        pass

print('min |a|={:.3f} g  max |a|={:.3f} g'.format(mag_min, mag_max))