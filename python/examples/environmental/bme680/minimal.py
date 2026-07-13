import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.environmental.bme680 import BME680Minimal
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
bme = BME680Minimal(transport)                           # Create BME680 driver, (transport)

for _ in range(5):
    t = bme.temperature()                                # Read temperature, () → float °C
    p = bme.pressure()                                   # Read pressure, () → float hPa
    h = bme.humidity()                                   # Read humidity, () → float %RH
    g = bme.gas_resistance()                             # Read gas resistance, () → float Ω
    print('{} C, {} hPa, {} %RH, {} Ohm'.format(t, p, h, g))
    machine.sleep(5000)
print('===DONE: 0 passed, 0 failed===')
