from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.rrh62000 import RRH62000Full
import time

connection = I2CConnection(0x69)                                         # Create I²C connection, (addr=0x69) → Connection
# The module has the fixed address 0x69; SEL (pin 5) must be at GND for I²C.
sensor = RRH62000Full(connection)                                        # Create RRH62000 driver, (connection, access_interval_ms=1000) → None
# Starts the start-up timers: I²C after 1 s, valid data after 3.3 s.
sensor.init()                                                            # Wait for start-up, check firmware version, () → None
# Reads the firmware version as a presence check.

print(sensor.firmware_version())                                         # Firmware version, () → (major, minor)
# Every access is at least 1 s after the previous one (tRD); the driver waits.
print(sensor.algorithm_version())                                        # TVOC algorithm version, () → (major, minor, patch)
# Version of the ZMOD4410 algorithm in the module.
print(sensor.unique_id().hex())                                          # Unique ID, () → bytes (6)
# Identifies the individual module.

print(sensor.read())                                                     # Main values, () → dict {pm1_ug_m3, pm2_5_ug_m3, pm10_ug_m3, temperature_c, humidity_rh, tvoc, eco2_ppm, iaq}
# Waits for DATA, reads the 37-byte block, checks the CRC; PM uses the KCl reference.
print(sensor.read_all())                                                 # Complete block, () → dict
# Status flags, 5 number-concentration bins (cm⁻³), PM KCl and smoke, T, RH, TVOC, eCO₂, IAQ.
print(sensor.read_status())                                              # Status flags, () → dict {raw, high_concentration, dust_accumulation, fan_speed, fan}
# Reads the current block without waiting for new data.
print(sensor.read_particles())                                           # Particle values, () → dict
# PM1/2.5/10 (KCl and cigarette smoke, µg/m³) and the number bins (cm⁻³).
print(sensor.data_ready())                                               # New sample available, () → bool
# DATA (0x40); a sample arrives every 3 s.

sensor.set_moving_average(20)                                            # Moving average, (count 1–60) → None
# More averages: smoother values, slower response (24 s at 10).
print(sensor.get_moving_average())                                       # Moving average, () → int
# 20.
sensor.set_fan_speed(80)                                                 # Fan speed, (percent 60–100) → None
# Lower speed is quieter; the default is 86 %.
print(sensor.get_fan_speed())                                            # Fan speed, () → int %
# 80.
sensor.set_cleaning_interval(3 * 24 * 3600)                              # Cleaning interval, (seconds, multiple of 30) → None
# Written as a 16-bit count of 30 s units, high byte first.
print(sensor.get_cleaning_interval())                                    # Cleaning interval, () → int s
# 259200.
sensor.set_cleaning_time(15)                                             # Cleaning time, (seconds 0–60) → None
# How long the fan runs at full speed during the automatic cleaning.
print(sensor.get_cleaning_time())                                        # Cleaning time, () → int s
# 15.

sensor.start_cleaning()                                                  # Start fan cleaning, () → None
# Blows dust out of the air channel.
time.sleep(15)
print(sensor.cleaning_done())                                            # Cleaning status, () → bool
# CSTATUS (0x74): True when completed.
print(sensor.read_mox_resistance())                                      # MOX resistance, () → int Ω
# Raw resistance of the TVOC sensing element.

sensor.sleep()                                                           # Sleep, () → None
# Stops the fan and the measurement.
time.sleep(5)
sensor.wake()                                                            # Wake up, () → None
# The next access waits 1 s, the next measurement 3.3 s.
sensor.soft_reset()                                                      # Reset, () → None
# Same as power-on: restarts the start-up timers.
print(sensor.read())                                                     # Main values, () → dict
# First sample after the reset.
