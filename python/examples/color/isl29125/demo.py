"""Demo for the ISL29125 — a colour sampler.

Prints the device ID and settings, samples R/G/B for 10 s and shows the colour
as #rrggbb, compares the 375 lux and 10 000 lux ranges and the 12/16-bit
resolutions, varies the IR compensation, reads green only, arms a threshold
interrupt on green with persistence 2, and finishes with power-down and soft
reset.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.color.isl29125 import ISL29125Full, SOURCE_THRESHOLD, STATUS_THRESHOLD, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = ISL29125Full(connection)                        # Create ISL29125 Full driver, (connection)

print('device id', hex(sensor.device_id()))              # Read DEVICE_ID, () → int


def colour(c):
    top = max(c['red'], c['green'], c['blue'], 1)
    return '#%02x%02x%02x' % (c['red'] * 255 // top, c['green'] * 255 // top, c['blue'] * 255 // top)


for _ in range(20):
    c = sensor.read()                                    # Read the three channels, () → dict
    print(colour(c), c['red'], c['green'], c['blue'])
    time.sleep(0.5)

for lux in (375, 10000):
    sensor.set_range(lux)                                # Set the range, (lux) → None
    time.sleep(0.4)
    print(lux, 'lux range', sensor.read_rgb())           # Read without waiting, () → (r, g, b)
for bits in (12, 16):
    sensor.set_resolution(bits)                          # Set the resolution, (bits) → None
    time.sleep(0.4)
    print(bits, 'bit full scale', (1 << bits) - 1, sensor.read_rgb())
sensor.set_range(10000)

for adjust in (0, 0x20, 0x3F):
    sensor.set_ir_compensation(False, adjust)            # Set the IR compensation, (offset bool, adjust) → None
    time.sleep(0.4)
    print('IR compensation', adjust, sensor.read_rgb())
sensor.set_ir_compensation(True, 0x3F)

sensor.set_mode('green')                                 # Green only, (mode) → None
time.sleep(0.3)
print('green only', sensor.read_green(), sensor.read_red(), sensor.read_blue())
sensor.set_mode('rgb')
print('ready', sensor.data_ready())                      # Query CONVENF, () → bool

base = sensor.read_green()                               # Read green, () → int
sensor.set_thresholds(max(0, base - base // 4), min(0xFFFF, base + base // 4))   # Set the window, (low, high) → None
print('window', sensor.get_thresholds())                 # Read the window, () → (low, high)
sensor.set_interrupt_channel('green')                    # Choose the channel, (channel) → None
sensor.set_persistence(2)                                # Set the persistence, (count) → None
sensor.enable_interrupt(SOURCE_THRESHOLD)                # Enable the interrupt, (source) → None
for _ in range(20):
    time.sleep(0.5)
    if sensor.poll_interrupt() & STATUS_THRESHOLD:       # Read and clear STATUS, () → int
        print('window crossed, green', sensor.read_green())
sensor.disable_interrupt(SOURCE_THRESHOLD)               # Disable the interrupt, (source) → None
print(hex(sensor.status()))                              # Raw STATUS, () → int
sensor.set_mode('power_down')                            # Power down, (mode) → None
sensor.soft_reset()                                      # Software reset, () → None
