"""Demo for the TMP102 — thermostat with one-shot sampling.

Reads the power-on configuration and limits, reads continuously at 4 Hz for
5 s, switches to 8 Hz, shows the 13-bit extended reading, then shuts down
and takes five timed one-shot readings. Limits of +1 °C / -1 °C around the
current temperature with fault queue 2 in interrupt mode report alerts while
the sensor is warmed with a finger. Finally the defaults are restored.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.temperature.tmp102 import TMP102Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = TMP102Full(connection)                          # Create TMP102 Full driver, (connection)

print('config 0x{:04X}'.format(sensor.read_config()))    # Read CONFIGURATION, () → int
print('limits', sensor.get_t_low(), sensor.get_t_high()) # Read T_LOW, () → float °C; Read T_HIGH, () → float °C

# --- Continuous reading at 4 Hz ---
end = time.time() + 5
while time.time() < end:
    print('{:.4f} °C (raw {})'.format(sensor.read_temperature(), sensor.read_raw()))  # Read temperature, () → float °C; Read raw count, () → int
    time.sleep(0.25)

# --- 8 Hz and the 13-bit extended mode ---
sensor.set_conversion_rate(8)                            # Set conversion rate, (hz) → None
sensor.set_extended_mode(True)                           # Set extended mode, (on) → None
time.sleep(0.2)
print('extended {:.4f} °C'.format(sensor.read_temperature()))  # Read temperature, () → float °C
sensor.set_extended_mode(False)                          # Set extended mode, (on) → None
sensor.set_conversion_rate(4)                            # Set conversion rate, (hz) → None

# --- Shutdown and timed one-shot readings ---
sensor.shutdown()                                        # Shut down, () → None
for _ in range(5):
    start = time.ticks_us()
    t = sensor.one_shot()                                # One-shot reading, () → float °C
    print('one-shot {:.4f} °C in {} us'.format(t, time.ticks_diff(time.ticks_us(), start)))
sensor.wake()                                            # Wake, () → None

# --- Thermostat around the current temperature ---
t = sensor.read_temperature()                            # Read temperature, () → float °C
sensor.set_t_high(t + 1.0)                               # Set T_HIGH, (celsius) → None
sensor.set_t_low(t - 1.0)                                # Set T_LOW, (celsius) → None
sensor.set_fault_queue(2)                                # Set fault queue, (count) → None
sensor.set_polarity(False)                               # Set ALERT polarity, (active_high) → None
sensor.set_thermostat_mode(True)                         # Set thermostat mode, (interrupt) → None
print('warm the sensor with a finger ...')
for _ in range(40):
    if sensor.poll_interrupt():                          # Poll the alert state, () → bool
        print('ALERT: {:.2f} °C, alert_state={}'.format(sensor.read_temperature(), sensor.alert_state()))  # Read alert state, () → bool
    time.sleep(0.25)

# --- Restore the power-on defaults ---
sensor.set_thermostat_mode(False)                        # Set thermostat mode, (interrupt) → None
sensor.set_fault_queue(1)                                # Set fault queue, (count) → None
sensor.set_t_high(80.0)                                  # Set T_HIGH, (celsius) → None
sensor.set_t_low(75.0)                                   # Set T_LOW, (celsius) → None
sensor.write_config(0x60A0)                              # Write CONFIGURATION, (value) → None
sensor.reset()                                           # General-call reset, () → None
