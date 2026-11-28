"""Complete example for the TSL2561 — exercise every method in the public API.

Reads lux and the raw channel counts, changes gain, integration time and the
package coefficients, auto-ranges, runs one host-timed manual integration,
programs the interrupt window and handles interrupts, then powers down.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.tsl2561 import TSL2561Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = TSL2561Full(connection)                         # Create TSL2561 Full driver, (connection)
                                                         # powers up, 1× gain, 402 ms, waits one integration

print(hex(sensor.read_id()))                             # Read the ID register, () → int
                                                         # bits 7:4 = 1 for the TSL2561, bits 3:0 revision
print(sensor.read_lux())                                 # Read illuminance, () → float lux
                                                         # raises ValueError on a saturated channel
print(sensor.read_raw())                                 # Read both channels, () → (int ch0, int ch1)
                                                         # one block read: ch0 visible+IR, ch1 IR
print(sensor.read_lux_raw())                             # Lux plus counts, () → (float lux, int ch0, int ch1)

sensor.set_gain(16)                                      # Set ADC gain, (gain 1|16) → None
                                                         # 16× for dim light; saturates indoors at ~1–2 klx
sensor.set_integration_time(101)                         # Set integration time, (ms 14|101|402) → None
                                                         # the conversion in progress keeps the old setting
print(sensor.get_gain(), sensor.get_integration_time())  # Current settings, () → int, () → int ms
time.sleep(0.25)
sensor.set_package('CS')                                 # Select lux coefficients, (package "T"|"FN"|"CL"|"CS") → None
                                                         # T, FN and CL share one set; CS has its own
print(sensor.read_lux())
sensor.set_package('T')
print(sensor.auto_range())                               # Auto-range and read lux, () → float lux
                                                         # 16×/402 ms … 1×/13.7 ms until no saturation and ch0 > 100

sensor.start_manual()                                    # Start a host-timed integration, () → None
                                                         # INTEG = 11, MANUAL = 1
time.sleep(0.2)
sensor.stop_manual()                                     # Stop the integration, () → None
                                                         # INTEG = 11, MANUAL = 0; counts are ready
print(sensor.read_raw())
sensor.set_integration_time(402)
sensor.set_gain(1)

sensor.set_thresholds(100, 20000)                        # Set channel-0 window, (low, high counts 0–65535) → None
                                                         # word writes of 0x2–0x3 and 0x4–0x5
print(sensor.get_thresholds())                           # Read the window, () → (int low, int high)
sensor.configure_interrupt(1, 2)                         # Interrupt mode, (mode 0|1|2, persist 0–15) → None
                                                         # level interrupt after 2 cycles outside the window
print(sensor.poll_interrupt())                           # Check ch0 against the window, () → bool
                                                         # does not clear the chip's interrupt
sensor.clear_interrupt()                                 # Release a level interrupt, () → None
                                                         # command byte 0xC0
sensor.on_interrupt(lambda ch0: print('interrupt', ch0))  # Subscribe, (callback(ch0), int_pin=None) → None
                                                         # falling edge on INT, or poll once per integration
time.sleep(10)
sensor.off_interrupt()                                   # Unsubscribe, () → None
sensor.configure_interrupt(0, 0)

print(sensor.is_powered())                               # Power state, () → bool
                                                         # CONTROL reads 0x03 when powered
sensor.power_down()                                      # Power down, () → None
                                                         # CONTROL = 0x00; registers keep their values
sensor.power_up()                                        # Power up, () → None
                                                         # CONTROL = 0x03; integration restarts
