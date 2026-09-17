from periph.connection.i2c_auto import I2CConnection
from periph.chips.magnetometer.hmc5883l import HMC5883LFull
import math
import time

connection = I2CConnection(0x1E)
hmc5883l = HMC5883LFull(connection)

# --- Configure for electronic compass ---
# 8-sample averaging at 15 Hz suppresses noise; ±1.3 Ga gain covers Earth's field (~0.5 Ga).
hmc5883l.configure(odr=15, averaging=8, gain=1)            # Configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → None

print('Electronic compass demo — hold sensor flat, rotate horizontally')
print('Vertical mount warning: |Z| > 30 µT indicates tilt compensation needed')
print()

# --- Sample and compute heading ---
# User rotates the sensor horizontally; we compute heading from X/Y axes.
# At n=5, user is prompted to tilt vertically to demonstrate Z-axis detection.
for n in range(10):
    while not hmc5883l.data_ready():                       # Check data ready, () → bool
        time.sleep(0.001)
    x, y, z = hmc5883l.magnetic_field()                    # Read magnetic field, () → (float T, float T, float T)

    # --- Compute heading from X and Y ---
    if x is not None and y is not None:
        heading = math.degrees(math.atan2(y, x))
        if heading < 0:
            heading += 360
        print('Heading: %.1f°' % heading)

    # --- Vertical mount detection ---
    if z is not None and abs(z) > 30e-6:
        print('[TILT WARNING] Z=%.1f µT — tilt compensation needed' % (z * 1e6))

    if n == 4:
        print('>>> Now tilt sensor vertically <<<')

    time.sleep(0.5)