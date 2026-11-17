from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.aht20 import AHT20Full
import time

connection = I2CConnection(0x38)                                         # Create I²C connection, (addr=0x38) → Connection
aht = AHT20Full(connection)                                             # Create AHT20 driver, (connection) → None

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

raw_rh, raw_t = aht.read_raw()                                          # Read raw 20-bit values, () → tuple (raw_humidity, raw_temperature)
                                                                       # same measurement as read(), undecoded
print(raw_rh, raw_t)

r = aht.read_with_crc()                                                # Read with CRC check, () → dict {temperature_c, humidity_pct, crc_ok}
                                                                       # reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
print(r['crc_ok'])

print(hex(aht.read_status()))                                          # Read status byte, () → int
                                                                       # bit 7 BUSY, bit 3 CAL

print(aht.dew_point())                                                 # Dew point, () → float °C
                                                                       # one fresh measurement, Magnus formula

aht.soft_reset()                                                       # Send soft reset command, () → None
                                                                       # sends 0xBA, waits 20 ms, re-runs the calibration check
print(aht.is_calibrated())
