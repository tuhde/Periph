from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.shtc3 import SHTC3Full
import time

connection = I2CConnection(0x70)                                         # Create I²C connection, (addr=0x70) → Connection
sensor = SHTC3Full(connection)                                           # Create SHTC3 driver, (connection) → None
sensor.init()                                                            # Soft reset and ID check, () → None

m = sensor.read()                                                        # Read temperature and humidity, () → dict {temperature_c °C, humidity_pct %RH}
                                                                         # normal mode, both CRCs verified, humidity clamped 0-100
print(m)
print(sensor.read_humidity())                                            # Read humidity only, () → float %RH
print(sensor.read_temperature())                                         # Read temperature only, () → float °C
print(sensor.read_raw())                                                 # Read raw codes, () → (int, int)
                                                                         # (raw_temperature, raw_humidity), 16-bit
print(hex(sensor.read_id()))                                             # Read the ID word, () → int
                                                                         # (id & 0x083F) == 0x0807 for the SHTC3

sensor.set_low_power(True)                                               # Select low-power mode, (on) → None
                                                                         # 0.7 ms measurement, slightly noisier
print(sensor.read())
sensor.set_low_power(False)

sensor.set_clock_stretching(True)                                        # Select clock stretching, (on) → None
                                                                         # needs a controller that tolerates SCL held low
print(sensor.read())
sensor.set_clock_stretching(False)

sensor.set_sleep_between(False)                                          # Sleep after every read?, (on) → None
                                                                         # off keeps the sensor idle (higher supply current)
print(sensor.read())
sensor.set_sleep_between(True)
sensor.wake()                                                            # Wake-up command, () → None
sensor.sleep()                                                           # Sleep command, () → None
print(sensor.dew_point())                                                # Compute dew point, () → float °C
                                                                         # one measurement, Magnus formula
sensor.soft_reset()                                                      # Soft reset, () → None
                                                                         # 0x805D, leaves the sensor idle
