from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.bh1750 import BH1750Full
import time

connection = I2CConnection(0x23)                                         # Create I²C connection, (addr=0x23) → Connection
sensor = BH1750Full(connection)                                          # Create BH1750 driver, (connection) → None
sensor.init()                                                            # Power on, () → None

print(sensor.read())                                                     # Measure illuminance, () → float lx
                                                                         # one-time H-resolution (1 lx), 180 ms
print(sensor.read_raw())                                                 # Read the raw count, () → int
                                                                         # 16-bit, one one-time measurement in the current mode
print(sensor.measure_once(sensor.MODE_H2))                               # One-time measurement, (mode MODE_H|MODE_H2|MODE_L) → float lx
                                                                         # MODE_H2 = 0.5 lx steps, MODE_L = 4 lx steps and 24 ms
print(sensor.measurement_time())                                         # Maximum measurement time, (mode=MODE_H) → float s
print(sensor.lux_per_count())                                            # Lux per raw count, (mode=MODE_H) → float

sensor.start_continuous(sensor.MODE_H)                                   # Start continuous mode, (mode=MODE_H) → None
                                                                         # first result after measurement_time()
time.sleep(0.2)
print(sensor.read_continuous())                                          # Read the latest result, () → float lx
                                                                         # no wait

sensor.set_measurement_time(138)                                         # Set MTreg, (mtreg 31-254) → None
                                                                         # default 69; larger = more sensitive and slower
print(sensor.get_measurement_time_register())                            # Get MTreg, () → int
                                                                         # cached, the chip has no read-back
sensor.set_sensitivity(1.0)                                              # Set sensitivity, (factor 0.45-3.68) → None
                                                                         # MTreg = round(69 x factor)

sensor.power_down()                                                      # Power down, () → None
sensor.power_on()                                                        # Power on, () → None
sensor.reset()                                                           # Clear the data register, () → None
                                                                         # powers on first; MTreg and mode are kept
