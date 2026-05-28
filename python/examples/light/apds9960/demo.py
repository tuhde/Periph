from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.light.apds9960 import APDS9960Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x39)
apds = APDS9960Full(transport)                             # Create APDS9960 driver, (transport) → APDS9960Full

# --- Monitor ambient light with adaptive integration time ---
# Start with the default 200 ms integration (ATIME=0xB6). When the clear
# channel approaches saturation (>90% of max count), halve the integration
# time by doubling ATIME to prevent overflow.
atime = 0xB6
apds.configure_als(atime, 1)                               # Configure ALS, (atime 0-255, again 0-3) → None

while True:
    while not apds.is_als_valid():                         # Check ALS data valid, () → bool
        time.sleep_ms(10)

    c, r, g, b = apds.color()                              # Read all RGBC channels, () → tuple(int, int, int, int)
    lux = -0.32466 * r + 1.57837 * g + -0.73191 * b
    print('C=%d R=%d G=%d B=%d  lux~%.0f' % (c, r, g, b, lux))

    # --- Adaptive integration: reduce time when saturated ---
    # At saturation the sensor clips; shortening integration recovers
    # headroom at the cost of reduced sensitivity in low light.
    if apds.is_als_saturated() and atime < 0xFE:           # Check ALS saturated, () → bool
        atime = atime + (256 - atime) // 2
        if atime > 0xFE:
            atime = 0xFE
        apds.configure_als(atime, 1)                       # Configure ALS, (atime 0-255, again 0-3) → None
        print('[SATURATED — reducing integration time, ATIME=0x%02X]' % atime)
        time.sleep_ms(250)

    time.sleep(1)
