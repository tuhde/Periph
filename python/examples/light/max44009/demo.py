"""Demo for the MAX44009 — an adaptive backlight controller.

Arms an interrupt window of ±30 % around the current illuminance; whenever the
light leaves the window for 2 s the callback maps the new level
logarithmically to a backlight level of 10–100 %, re-centres the window and
re-arms it. Prints the exponent / mantissa once a second and runs for 60 s —
no polling of the lux registers.
"""

import math
import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.max44009 import MAX44009Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = MAX44009Full(connection)                        # Create MAX44009 Full driver, (connection)

print('config', sensor.get_config())                     # Read CONFIG, () → dict
lux = sensor.lux()                                       # Read illuminance, () → float lux
print('lux {:.2f}, resolution {:.3f} lx/LSB'.format(lux, sensor.resolution()))  # Lux per LSB, () → float


def backlight(level):
    """Map lux logarithmically (0.1–10 000 lx) to a backlight level of 10–100 %."""
    level = max(0.1, min(10000.0, level))
    return 10.0 + 90.0 * (math.log10(level) + 1.0) / 5.0


def arm(center):
    sensor.set_thresholds(center * 0.7, center * 1.3)    # Set the interrupt window, (low_lux, high_lux) → None
    sensor.enable_interrupt()                            # Enable the interrupt, () → None


def on_light_change(status):
    now = sensor.lux()
    print('lux {:.2f} -> backlight {:.0f} %'.format(now, backlight(now)))
    arm(now)                                             # re-centre the window around the new value


sensor.set_threshold_delay(2.0)                          # Set the threshold delay, (seconds) → None
arm(lux)
sensor.on_interrupt(on_light_change)                     # Subscribe, (callback(status), int_pin=None) → None
print('backlight {:.0f} %'.format(backlight(lux)))

for _ in range(60):
    exponent, mantissa, over = sensor.read_raw()         # Read exponent and mantissa, () → (int, int, bool)
    print('E={} M={} overrange={}'.format(exponent, mantissa, sensor.is_overrange()))  # Query the last reading, () → bool
    time.sleep(1)

sensor.disable_interrupt()                               # Disable the interrupt, () → None
sensor.off_interrupt()                                   # Unsubscribe, () → None
