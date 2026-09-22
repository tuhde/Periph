from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.bmp085 import BMP085Full

connection = I2CConnection(0x77)
bmp = BMP085Full(connection)                             # Create BMP085 driver, (connection, oss=0)
cid = bmp.chip_id()                                    # Read chip ID, () → int
                                                      # returns 0x55 for BMP085
oss = bmp.oversampling()                               # Read OSS, () → int 0–3
bmp.set_oversampling(BMP085Full.OSS_STANDARD)           # Set OSS, (oss 0–3) → None
                                                      # changes conversion time vs resolution trade-off
t = bmp.temperature()                                  # Read temperature, () → float C
p = bmp.pressure()                                     # Read pressure, () → float Pa
alt = bmp.altitude()                                  # Compute altitude, (sea_level_pa=101325.0) → float m
                                                      # uses barometric formula to convert pressure to metres
slp = bmp.sea_level_pressure(alt)                      # Compute sea-level pressure, (altitude_m) → float Pa
bmp.reset()                                           # Soft reset chip, () → None
                                                      # re-reads calibration after reset
print('T={} C, P={} Pa, alt={} m, slp={} Pa'.format(t, p, alt, slp))
print('===DONE: 0 passed, 0 failed===')