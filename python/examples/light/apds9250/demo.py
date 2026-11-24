"""Demo for the APDS-9250 — a colour sampler.

Prints the part ID and settings, samples R/G/B/IR for 10 s and shows the colour
as #rrggbb, compares ALS/IR mode, steps gain and resolution with normalised
counts, then arms a variation interrupt and a threshold interrupt on the green
channel, and finishes with disable and soft reset.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.apds9250 import APDS9250Full, SOURCE_THRESHOLD, SOURCE_VARIATION, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = APDS9250Full(connection)                        # Create APDS-9250 Full driver, (connection)

print('part/revision', sensor.part_id())                 # Read PART_ID, () → (int, int)
print('resolution', sensor.get_resolution(), 'bit, rate', sensor.get_measurement_rate(),
      'ms, gain', sensor.get_gain())                     # Read the settings, () → int each


def colour(c):
    top = max(c['red'], c['green'], c['blue'], 1)
    return '#%02x%02x%02x' % (c['red'] * 255 // top, c['green'] * 255 // top, c['blue'] * 255 // top)


for _ in range(20):
    c = sensor.read()                                    # Read the four channels, () → dict
    print(colour(c), 'IR fraction %.2f' % (c['ir'] / max(1, c['ir'] + c['green'])))
    time.sleep(0.5)

sensor.set_mode(False)                                   # Select ALS/IR mode, (colour bool) → None
print('ALS/IR', sensor.read_green(), sensor.read_ir())   # Read green and IR, () → int each
sensor.set_mode(True)                                    # Back to colour mode, (colour bool) → None

for gain in (1, 3, 6, 9, 18):
    sensor.set_gain(gain)                                # Set the gain, (gain) → None
    time.sleep(0.3)
    g = sensor.read_green()                              # Read green, () → int
    print('gain', gain, 'green', g, 'normalized %.0f' % sensor.normalized(g))
sensor.set_gain(3)
for bits in (13, 16, 17, 18, 19, 20):
    sensor.set_resolution(bits)                          # Set the resolution, (bits) → None
    time.sleep(sensor.integration_time() * 2 + 0.05)
    g = sensor.read_green()
    print(bits, 'bit full scale', (1 << bits) - 1, 'green', g, 'normalized %.0f' % sensor.normalized(g))
sensor.set_resolution(18)
sensor.set_measurement_rate(100)                         # Set the rate, (milliseconds) → None

sensor.set_interrupt_channel('green')                    # Choose the channel, (channel) → None
sensor.set_persistence(2)                                # Set the persistence, (count) → None
sensor.set_variance(64)                                  # Set the variation threshold, (counts) → None
sensor.enable_interrupt(SOURCE_VARIATION)                # Enable the variation interrupt, (source) → None
for _ in range(20):
    time.sleep(0.5)
    status = sensor.poll_interrupt()                     # Read and clear MAIN_STATUS, () → int
    if status & 0x10:
        print('variation interrupt, green', sensor.read_green())

base = sensor.read_green()
sensor.set_thresholds(max(0, base - base // 4), base + base // 4)   # Set the window, (low, high) → None
sensor.enable_interrupt(SOURCE_THRESHOLD)                # Enable the threshold interrupt, (source) → None
sensor.on_interrupt(lambda s: print('threshold interrupt', hex(s)))   # Subscribe, (callback, int_pin=None) → None
time.sleep(10)
sensor.off_interrupt()                                   # Unsubscribe, () → None
sensor.disable_interrupt(SOURCE_THRESHOLD)               # Disable the interrupt, (source) → None
print(sensor.get_thresholds(), hex(sensor.status()), sensor.data_ready())
print(sensor.read_channels(), sensor.read_red(), sensor.read_blue())
sensor.disable()                                         # Standby, () → None
sensor.soft_reset()                                      # Software reset, () → None
