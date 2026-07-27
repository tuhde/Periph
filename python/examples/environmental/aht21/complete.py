from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.aht21 import AHT21Full
import time

connection = I2CConnection(0x38)                                         # Create I²C connection, (addr=0x38) → Connection
aht = AHT21Full(connection)                                             # Create AHT21 driver, (connection) → None

print(aht.is_calibrated())                                             # Check calibration status, () → bool
                                                                       # reads CAL bit from status byte
print(aht.is_busy())                                                   # Check busy status, () → bool
                                                                       # reads BUSY bit from status byte

r = aht.read()                                                         # Trigger measurement, () → dict {temperature_c, humidity_pct}
                                                                       # sends 0xAC trigger, waits 80 ms, decodes 6 bytes
print(r['temperature_c'], r['humidity_pct'])

print(aht.read_temperature())                                          # Read temperature only, () → float °C
                                                                       # triggers full measurement, returns temperature_c
print(aht.read_humidity())                                             # Read humidity only, () → float %RH
                                                                       # triggers full measurement, returns humidity_pct

rc = aht.read_with_crc()                                               # Read with CRC verification, () → dict {temperature_c, humidity_pct, crc_ok}
                                                                       # reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
print(rc['temperature_c'], rc['humidity_pct'], rc['crc_ok'])

aht.soft_reset()                                                       # Send soft reset command, () → None
                                                                       # sends 0xBA, waits 20 ms for recovery
