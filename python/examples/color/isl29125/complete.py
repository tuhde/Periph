"""Complete example for the ISL29125 — every API method of the Full driver.

Exercises the channel reads, mode, range, resolution, synchronisation, IR
compensation, thresholds, persistence, interrupt configuration, status, device
ID and the software reset.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.color.isl29125 import (
    ISL29125Full, SOURCE_THRESHOLD, SOURCE_CONVERSION, I2C_ADDRESS)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = ISL29125Full(connection)                        # Create ISL29125 Full driver, (connection)

print('device id', hex(sensor.device_id()))              # Read DEVICE_ID, () → int
                                                         # 0x7D for the ISL29125
print(sensor.read())                                     # Read the three channels, () → dict
print(sensor.read_red())                                 # Read the red channel, () → int
print(sensor.read_green())                               # Read the green channel, () → int
print(sensor.read_blue())                                # Read the blue channel, () → int
print(sensor.read_rgb())                                 # Block-read without waiting, () → (r, g, b)
print(sensor.data_ready())                               # Query CONVENF, () → bool
                                                         # clears the status flags on the chip
sensor.set_mode('green')                                 # Select the channels, (mode) → None
                                                         # power_down, standby, green, red, blue, rgb, green_red, green_blue
sensor.set_mode('rgb')                                   # Back to all three channels, (mode) → None
sensor.set_range(375)                                    # Set the full-scale range, (lux) → None
                                                         # 375 or 10000
sensor.set_range(10000)
sensor.set_resolution(12)                                # Set the ADC resolution, (bits) → None
                                                         # 12 or 16
sensor.set_resolution(16)
sensor.set_sync(False)                                   # Select the conversion start, (external bool) → None
                                                         # True: rising edge of INT starts the conversion
sensor.set_ir_compensation(False, 0x3F)                  # Set the IR compensation, (offset bool, adjust) → None
                                                         # adjust 0-63; offset selects codes 106-169
sensor.set_ir_compensation(True, 0x3F)
sensor.set_thresholds(1000, 50000)                       # Set the threshold window, (low, high) → None
print(sensor.get_thresholds())                           # Read the window, () → (low, high)
sensor.set_interrupt_channel('green')                    # Choose the monitored channel, (channel) → None
                                                         # none, green, red or blue
sensor.set_persistence(2)                                # Set the persistence, (count) → None
                                                         # 1, 2, 4 or 8 integration cycles
sensor.enable_interrupt(SOURCE_THRESHOLD)                # Enable the interrupt, (source) → None
                                                         # SOURCE_THRESHOLD or SOURCE_CONVERSION
sensor.on_interrupt(lambda s: print('interrupt', hex(s)))   # Subscribe, (callback, int_pin=None) → None
time.sleep(2)
sensor.off_interrupt()                                   # Unsubscribe, () → None
print(hex(sensor.poll_interrupt()))                      # Read and clear STATUS, () → int
sensor.disable_interrupt(SOURCE_CONVERSION)              # Disable the interrupt, (source) → None
print(hex(sensor.status()))                              # Raw STATUS, () → int
sensor.set_mode('power_down')
sensor.soft_reset()                                      # Software reset, () → None
                                                         # all registers to defaults
