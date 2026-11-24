"""Complete example for the APDS-9250 — exercise every method in the public API.

Reads the channels singly and as a block, changes mode, resolution, rate and
gain, programs the threshold and variation interrupts and handles interrupts.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.apds9250 import APDS9250Full, SOURCE_THRESHOLD, SOURCE_VARIATION, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = APDS9250Full(connection)                        # Create APDS-9250 Full driver, (connection)

print(sensor.part_id())                                  # Read PART_ID, () → (int part, int revision)
                                                         # (0xB, 0x5) for the APDS-9250
print(sensor.read())                                     # Read all channels, () → dict {red, green, blue, ir}
print(sensor.read_red())                                 # Read the red channel, () → int count
print(sensor.read_green())                               # Read the green / ALS channel, () → int count
print(sensor.read_blue())                                # Read the blue channel, () → int count
print(sensor.read_ir())                                  # Read the IR channel, () → int count
print(sensor.data_ready())                               # Query LS_DATA, () → bool
                                                         # clears the status flags on the chip
print(sensor.read_channels())                            # Block-read without waiting, () → (red, green, blue, ir)

sensor.set_mode(False)                                   # Select ALS/IR mode, (colour bool) → None
                                                         # False: only green and IR are active
sensor.set_mode(True)                                    # Back to colour mode, (colour bool) → None
sensor.disable()                                         # Standby, () → None
sensor.enable()                                          # Activate the sensor, () → None

sensor.set_resolution(16)                                # Set the ADC resolution, (bits) → None
                                                         # 13, 16, 17, 18, 19 or 20; restarts the measurement
sensor.set_measurement_rate(200)                         # Set the measurement rate, (milliseconds) → None
                                                         # 25, 50, 100, 200, 500, 1000 or 2000
sensor.set_gain(6)                                       # Set the gain, (gain) → None
                                                         # 1, 3, 6, 9 or 18; restarts the measurement
print(sensor.get_resolution())                           # Read the resolution, () → int bits
print(sensor.get_measurement_rate())                     # Read the rate, () → int ms
print(sensor.get_gain())                                 # Read the gain, () → int
print(sensor.integration_time())                         # Integration time, () → float s
print(sensor.normalized(sensor.read_green()))            # Counts per gain-second, (counts) → float

sensor.set_thresholds(1000, 100000)                      # Set the threshold window, (low, high) → None
                                                         # 20-bit counts
print(sensor.get_thresholds())                           # Read the window, () → (int low, int high)
sensor.set_interrupt_channel('green')                    # Choose the monitored channel, (channel) → None
                                                         # 'ir', 'green', 'red' or 'blue'
sensor.set_persistence(2)                                # Set the persistence, (count) → None
                                                         # 1–16 consecutive events
sensor.set_variance(64)                                  # Set the variation threshold, (counts) → None
                                                         # 8 … 1024, power of two

sensor.enable_interrupt(SOURCE_VARIATION)                # Enable the interrupt, (source) → None
                                                         # SOURCE_THRESHOLD or SOURCE_VARIATION
sensor.on_interrupt(lambda status: print('interrupt', hex(status)))   # Subscribe, (callback(status), int_pin=None) → None
                                                         # falling edge on INT, or a 50 ms poll without a pin
time.sleep(10)
print(hex(sensor.poll_interrupt()))                      # Read and clear MAIN_STATUS, () → int
                                                         # 0x20 power-on, 0x10 interrupt, 0x08 data
sensor.off_interrupt()                                   # Unsubscribe, () → None
sensor.disable_interrupt(SOURCE_THRESHOLD)               # Disable the interrupt, (source) → None
print(hex(sensor.status()))                              # Raw MAIN_STATUS, () → int
sensor.soft_reset()                                      # Software reset, () → None
                                                         # all registers to defaults, sensor in standby
