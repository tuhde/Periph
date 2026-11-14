from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.am2315 import AM2315Full
import time

connection = I2CConnection(0x5C)                                         # Create I²C connection, (addr=0x5C) → Connection
sensor = AM2315Full(connection)                                          # Create AM2315 driver, (connection) → None

r = sensor.read()                                                        # Read temperature and humidity, () → dict {temperature_c, humidity_pct}
                                                                         # returns the previous measurement; reads are kept >= 2 s apart
print(r['temperature_c'], r['humidity_pct'])
print(sensor.read_temperature())                                         # Read temperature only, () → float °C
print(sensor.read_humidity())                                            # Read humidity only, () → float %RH
print(sensor.read_raw())                                                 # Read raw words, () → (int, int)
                                                                         # (humidity x10, temperature x10 as sign + magnitude)
print(sensor.read_fresh())                                               # Real-time value, () → dict {temperature_c, humidity_pct}
                                                                         # two reads >= 2 s apart; returns the second
print(sensor.read_registers(0x08, 7))                                    # Read registers, (start, count) → bytes
                                                                         # function 0x03, 1-10 registers, CRC verified
print(sensor.read_device_info())                                         # Read device information, () → dict {model, version, device_id}
print(sensor.read_user_registers())                                      # Read user registers, () → (int, int)
sensor.write_user_registers(0x1234, 0xABCD)                              # Write user registers, (user1, user2) → None
sensor.write_registers(0x10, bytes([0x00, 0x01]))                        # Write registers, (start, data) → None
                                                                         # only the user registers 0x10-0x13 are allowed
sensor.wake()                                                            # Wake the sensor, () → None
                                                                         # dummy write, the NACK is ignored
