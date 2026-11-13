"""Complete example for the MAX44009 — exercise every method in the public API.

Reads the illuminance and the raw exponent / mantissa, switches between
automatic and manual ranging and between low-power and continuous mode,
programs the interrupt window and delay, and handles one interrupt.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.max44009 import MAX44009Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = MAX44009Full(connection)                        # Create MAX44009 Full driver, (connection)
                                                         # writes the reset state, clears INT_STATUS, waits 0.9 s

print(sensor.lux())                                      # Read illuminance, () → float lux
                                                         # 2^E × M × 0.045; 188006.4 on overrange
print(sensor.read_raw())                                 # Read exponent and mantissa, () → (int E, int M, bool overrange)
                                                         # one consistent sample: LUX_HIGH, LUX_LOW, LUX_HIGH
print(sensor.is_overrange())                             # Query the last reading, () → bool
                                                         # True if the last exponent field was 15
print(sensor.resolution())                               # Lux per LSB, () → float lux
                                                         # 2^E × 0.045 for the last reading

sensor.set_continuous(True)                              # Select measurement mode, (continuous bool) → None
                                                         # True: a new measurement as soon as one finishes
sensor.set_manual(100, False)                            # Select manual range, (integration_ms, divide_by_8=False) → None
                                                         # nearest of 800/400/200/100/50/25/12.5/6.25 ms
print(sensor.get_config())                               # Read CONFIG, () → dict
                                                         # {continuous, manual, divide_by_8, integration_ms}
sensor.set_automatic()                                   # Select automatic range, () → None
                                                         # the chip chooses the current divider and integration time
sensor.set_continuous(False)                             # Back to one measurement per 800 ms, (continuous bool) → None

sensor.set_thresholds(50.0, 500.0)                       # Set the interrupt window, (low_lux, high_lux) → None
                                                         # 4 mantissa MSBs: about 1/16 to 1/8 of the value
print(sensor.get_thresholds())                           # Read the window, () → (float low_lux, float high_lux)
sensor.set_threshold_delay(2.0)                          # Set the threshold delay, (seconds) → None
                                                         # 0–25.5 s in 0.1 s steps
print(sensor.get_threshold_delay())                      # Read the delay, () → float s

sensor.enable_interrupt()                                # Enable the interrupt, () → None
                                                         # INT_ENABLE = 1
sensor.on_interrupt(lambda status: print('interrupt', status, sensor.lux()))   # Subscribe, (callback(status), int_pin=None) → None
                                                         # falling edge on INT, or a 100 ms poll without a pin
time.sleep(10)
print(sensor.poll_interrupt())                           # Read and clear INT_STATUS, () → int
                                                         # bit 0 = SOURCE_THRESHOLD
sensor.off_interrupt()                                   # Unsubscribe, () → None
sensor.disable_interrupt()                               # Disable the interrupt, () → None
                                                         # INT_ENABLE = 0; also clears a pending status
