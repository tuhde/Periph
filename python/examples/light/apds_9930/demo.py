"""Demo for the APDS-9930 — adaptive backlight + screen-lock scenario.

Logs lux and proximity once per second and prints a "dim backlight"
action when the environment is dim, and a "disable screen" action when
an object comes close. Mirrors the intended use in cell phones — one
sensor drives both ambient brightness adaptation and face-detection
screen lock.
"""

import time

from periph.chips.light.apds_9930 import APDS9930Full
from periph.connection.i2c_auto import I2CConnection

# --- Configure for adaptive backlight + screen-lock monitoring ---
# Defaults (ATIME=0xDB, 1x AGAIN, 8-pulse proximity, 100 mA drive) are
# already a good fit: 101 ms ALS integration rejects 50/60 Hz
# fluorescent flicker, and 8 pulses at 100 mA gives reliable readings
# to ~100 mm. The demo only adjusts the thresholds that drive the
# two named actions, leaving the chip configuration untouched.
connection = I2CConnection(0x39)                                       # Create I2C connection, (addr=0x39, bus=None) → I2CConnection
apds = APDS9930Full(connection)                                        # Construct APDS-9930 Full, (connection) → APDS9930Full
                                                                     # default 101 ms ALS integration, 8-pulse proximity, 100 mA drive

# --- Sample lux and proximity once per second for 30 cycles ---
# The user is encouraged to cover the sensor with a hand (proximity
# rises) and to dim/undim the room light to watch both action lines
# fire.
DIM_LUX_THRESHOLD = 10.0       # below this, recommend dimming backlight
PROX_SCREEN_OFF = 400          # above this, recommend disabling screen
for _ in range(30):
    time.sleep(1.0)
    lx = apds.lux()                                                   # Read ambient illuminance, () → float lx
                                                                     # IR-compensated lux via Ch0/Ch1 difference
    p = apds.proximity()                                              # Read proximity count, () → int count
                                                                     # 16-bit ADC value; higher = closer
    print('lux={:.1f} lx  proximity={}'.format(lx, p))
    if lx < DIM_LUX_THRESHOLD:
        print('  -> dim backlight')
    if p > PROX_SCREEN_OFF:
        print('  -> disable screen')

connection.close()