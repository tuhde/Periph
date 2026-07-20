import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.environmental.bme280 import BME280Minimal
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
bme = BME280Minimal(transport)                         # Create BME280 driver, (transport, bus_type='i2c')

for _ in range(5):
    t = bme.temperature()                              # Read temperature, () → float °C
    p = bme.pressure()                                 # Read pressure, () → float hPa
    h = bme.humidity()                                 # Read humidity, () → float %RH
    print('{} C, {} hPa, {} %RH'.format(t, p, h))
    machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')
