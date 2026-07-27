from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.ens160 import ENS160Minimal
import time

connection = I2CConnection(0x52)                               # Create I²C connection, (addr=0x52) → Connection
sensor = ENS160Minimal(connection)                            # Create ENS160 driver, (connection)

print('Waiting for sensor warm-up...')
while True:
    try:
        data = sensor.read_air_quality()                     # Read air quality, () → dict {aqi, tvoc_ppb, eco2_ppm}
        break
    except RuntimeError:
        time.sleep(1)

print('AQI={} TVOC={} ppb eCO2={} ppm'.format(data['aqi'], data['tvoc_ppb'], data['eco2_ppm']))
for _ in range(9):
    data = sensor.read_air_quality()                         # Read air quality, () → dict {aqi, tvoc_ppb, eco2_ppm}
    print('AQI={} TVOC={} ppb eCO2={} ppm'.format(data['aqi'], data['tvoc_ppb'], data['eco2_ppm']))
    time.sleep(1)
