"""Complete example for the TMP102 — exercise every method in the public API.

Constructs the Full driver, reads the temperature and raw count, changes the
conversion rate, enables extended mode, programs the limits, fault queue,
polarity and thermostat mode, takes a one-shot reading, checks the alert
state and subscribes to ALERT events. The general-call reset is shown last.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.temperature.tmp102 import TMP102Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = TMP102Full(connection)                          # Create TMP102 Full driver, (connection)

t = sensor.read_temperature()                            # Read temperature, () → float °C
                                                         # decodes TEMPERATURE, 0.0625 °C two's complement
print('temperature {:.4f} °C'.format(t))
print('raw', sensor.read_raw())                          # Read raw count, () → int
                                                         # signed 12/13-bit count, 0.0625 °C per LSB

print('config 0x{:04X}'.format(sensor.read_config()))    # Read CONFIGURATION, () → int
                                                         # reset value 0x60A0
sensor.write_config(sensor.read_config())                # Write CONFIGURATION, (value) → None
                                                         # read-only bits are masked out

sensor.set_conversion_rate(8)                            # Set conversion rate, (hz) → None
                                                         # 0.25, 1, 4 or 8 Hz
sensor.set_extended_mode(True)                           # Set extended mode, (on) → None
                                                         # EM bit, 13-bit readings up to +255.9375 °C
time.sleep(0.2)
print('extended {:.4f} °C'.format(sensor.read_temperature()))
sensor.set_extended_mode(False)                          # Set extended mode, (on) → None
                                                         # back to 12 bit

sensor.set_t_high(30.0)                                  # Set T_HIGH, (celsius) → None
                                                         # rounded to 0.0625 °C
sensor.set_t_low(10.0)                                   # Set T_LOW, (celsius) → None
                                                         # rounded to 0.0625 °C
print('high', sensor.get_t_high())                       # Read T_HIGH, () → float °C
                                                         # same format as TEMPERATURE
print('low', sensor.get_t_low())                         # Read T_LOW, () → float °C
                                                         # same format as TEMPERATURE

sensor.set_fault_queue(2)                                # Set fault queue, (count) → None
                                                         # 1, 2, 4 or 6 consecutive faults
sensor.set_polarity(False)                               # Set ALERT polarity, (active_high) → None
                                                         # POL bit, False = active low
sensor.set_thermostat_mode(False)                        # Set thermostat mode, (interrupt) → None
                                                         # TM bit, False = comparator
print('alert', sensor.alert_state())                     # Read alert state, () → bool
                                                         # AL bit corrected for POL

sensor.shutdown()                                        # Shut down, () → None
                                                         # SD bit, stops after the current conversion
print('one-shot {:.4f} °C'.format(sensor.one_shot()))    # One-shot reading, () → float °C
                                                         # OS=1, polls until done, restores the mode
sensor.wake()                                            # Wake, () → None
                                                         # resume continuous conversion


def on_alert(temperature):
    print('ALERT at {:.2f} °C'.format(temperature))


sensor.on_interrupt(on_alert)                            # Subscribe to ALERT, (callback, int_pin=None) → None
                                                         # callback(temperature_c)
print('polling', sensor.poll_interrupt())                # Poll the alert state, () → bool
                                                         # reads CONFIGURATION and TEMPERATURE
time.sleep(3)
sensor.off_interrupt()                                   # Unsubscribe, () → None
                                                         # stop delivery

sensor.set_conversion_rate(4)                            # Set conversion rate, (hz) → None
                                                         # back to the power-on default
sensor.set_t_high(80.0)                                  # Set T_HIGH, (celsius) → None
sensor.set_t_low(75.0)                                   # Set T_LOW, (celsius) → None
sensor.reset()                                           # General-call reset, () → None
                                                         # resets every general-call device on the bus
