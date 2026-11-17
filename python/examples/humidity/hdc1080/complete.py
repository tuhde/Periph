from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hdc1080 import HDC1080Full

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = HDC1080Full(connection)                                         # Create HDC1080 driver, (connection) → None

sensor.init()                                                            # Write default configuration, () → None
print('serial id 0x%010X' % sensor.serial_id())                          # Read 40-bit serial ID, () → int
print('config 0x%04X' % sensor.read_config())                            # Read configuration register, () → int
t, rh = sensor.read()                                                    # Read temperature and humidity, () → (float, float)
print('%.2f C %.1f %%RH' % (t, rh))
print('temperature %.2f C' % sensor.read_temperature())                  # Read temperature only, () → float °C
print('humidity %.1f %%RH' % sensor.read_humidity())                     # Read humidity only, () → float %RH
print('raw', sensor.read_raw())                                          # Read raw words, () → (int, int)
sensor.set_resolution(11, 8)                                             # Set resolution, (temperature_bits, humidity_bits) → None
print('conversion time %.2f ms' % (sensor.conversion_time() * 1000))     # Typical conversion time, () → float s
sensor.set_resolution(14, 14)
sensor.set_heater(True)                                                  # Switch the heater, (on) → None
print('heater on:', sensor.is_heater_on())                               # Heater state, () → bool
sensor.set_heater(False)
print('battery low:', sensor.battery_low())                              # VDD below 2.8 V, () → bool
sensor.software_reset()                                                  # Software reset, () → None

print('manufacturer 0x%04X' % sensor.manufacturer_id())                  # Manufacturer ID (0x5449), () → int
print('device 0x%04X' % sensor.device_id())                              # Device ID (0x1050), () → int
