from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.rrh62000 import RRH62000Minimal

connection = I2CConnection(0x69)                                         # Create I²C connection, (addr=0x69) → Connection
sensor = RRH62000Minimal(connection)                                     # Create RRH62000 driver, (connection, access_interval_ms=1000) → None
sensor.init()                                                            # Wait for start-up, check firmware version, () → None

while True:
    r = sensor.read()                                                    # Wait for a new sample, () → dict {pm1_ug_m3, pm2_5_ug_m3, pm10_ug_m3 µg/m³, temperature_c °C, humidity_rh %RH, tvoc µg/m³, eco2_ppm ppm, iaq}
    print('PM1 %.1f  PM2.5 %.1f  PM10 %.1f ug/m3  %.2f C  %.2f %%RH  TVOC %d ug/m3  eCO2 %d ppm  IAQ %.2f' % (
        r['pm1_ug_m3'], r['pm2_5_ug_m3'], r['pm10_ug_m3'], r['temperature_c'], r['humidity_rh'],
        r['tvoc'], r['eco2_ppm'], r['iaq']))
