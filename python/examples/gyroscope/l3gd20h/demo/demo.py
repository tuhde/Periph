from periph.connection.i2c_linux import I2CConnection
from periph.chips.gyroscope.l3gd20h import L3GD20HFull
import time
import math

conn = I2CConnection(bus=1, addr=0x6A)
gyro = L3GD20HFull(conn)                                            # Create L3GD20H driver, (connection, bus_type='i2c')

# --- Configure for shake detection at 190 Hz, ±500 dps ---
# 190 Hz ODR provides good temporal resolution for shake detection;
# ±500 dps full scale gives 17.5 mdps/digit sensitivity, suitable for
# detecting moderate to strong motion without clipping.
gyro.configure(odr=1, bw=0, full_scale=1)                           # Configure ADC, (odr 0-3, bw 0-3, full_scale 0-2) -> None

print("L3GD20H shake detector running. Shake the device...")
print("Press Ctrl+C to stop.\n")

try:
    while True:
        if gyro.data_ready():                                       # Check data ready, () -> bool
            x, y, z = gyro.gyro()                                   # Read angular rate, () -> (float, float, float) rad/s
            magnitude = math.sqrt(x*x + y*y + z*z)
            if magnitude > 1.0:
                print("SHAKE DETECTED: mag={:.3f} rad/s (x={:.3f} y={:.3f} z={:.3f})".format(
                    magnitude, x, y, z))
            else:
                print("x={:.3f} y={:.3f} z={:.3f} rad/s  mag={:.3f}".format(x, y, z, magnitude))
except KeyboardInterrupt:
    print("\nStopped.")