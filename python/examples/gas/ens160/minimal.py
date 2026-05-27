import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.gas.ens160 import ENS160Minimal
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
sensor = ENS160Minimal(transport)                        # Create ENS160 driver, (transport)

print('Waiting for sensor warm-up...')
while sensor.status() != 0:                              # Poll validity, () → int 0–3
    print('Status: {}'.format(sensor.status()))
    machine.sleep(1000)

for _ in range(10):
    data = sensor.read_air_quality()                     # Read air quality, () → dict {aqi, tvoc_ppb, eco2_ppm}
    print('AQI={} TVOC={} ppb eCO2={} ppm'.format(data['aqi'], data['tvoc_ppb'], data['eco2_ppm']))
    machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')
