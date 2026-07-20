from periph.transport.i2c_auto import I2CTransport
from periph.chips.gas.ens160 import ENS160Minimal
import time

transport = I2CTransport(0x52)                               # Create I²C transport, (addr=0x52) → Transport
sensor = ENS160Minimal(transport)                            # Create ENS160 driver, (transport)

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
