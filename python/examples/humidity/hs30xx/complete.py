from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hs30xx import HS30xxFull
import time

connection = I2CConnection(0x44)                                         # Create I²C connection, (addr=0x44) → Connection
sensor = HS30xxFull(connection)                                          # Create HS30xx driver, (connection) → None

d = sensor.read()                                                        # Read temperature and humidity, () → dict {temperature_c, humidity_pct}
                                                                         # request, 40 ms wait, 4-byte fetch; one retry on stale data
print(d['temperature_c'], d['humidity_pct'])
print(sensor.read_temperature())                                         # Read temperature only, () → float °C
print(sensor.read_humidity())                                            # Read humidity only, () → float %RH
                                                                         # 2-byte fetch
print(sensor.read_raw())                                                 # Read raw values, () → (int, int, int)
                                                                         # (humidity, temperature, status); 14-bit, status 0 = valid, 1 = stale
sensor.set_measurement_wait(60)                                          # Override the post-request wait, (ms) → None
                                                                         # default 40 ms; lower resolutions need less
print(sensor.dew_point())                                                # Compute dew point, () → float °C
                                                                         # one measurement, Magnus formula


def power_cycle():
    # Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
    time.sleep(0.1)


# NVM access only works within 10 ms after power-up, so it needs the helper above.
print(sensor.get_resolution(power_cycle))                                # Get NVM resolution, (power_cycle=None) → (int, int)
                                                                         # (humidity_bits, temperature_bits)
sensor.set_resolution(12, 12, power_cycle)                               # Set NVM resolution, (humidity_bits, temperature_bits, power_cycle=None) → None
                                                                         # 8, 10, 12 or 14 bit; None leaves a channel unchanged
print(hex(sensor.read_id(power_cycle)))                                  # Read the 32-bit sensor ID, (power_cycle=None) → int
sensor.enter_programming_mode()                                          # Enter programming mode, () → None
                                                                         # only within 10 ms after power-up
print(hex(sensor.read_nvm(0x06)))                                        # Read an NVM register, (reg) → int
                                                                         # 0x06 humidity / 0x11 temperature resolution, 0x1E / 0x1F ID
sensor.exit_programming_mode()                                           # Leave programming mode, () → None
