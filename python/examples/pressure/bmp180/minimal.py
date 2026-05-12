import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.pressure.bmp180 import BMP180Minimal
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, 0x77)
bmp = BMP180Minimal(transport)                           # Create BMP180 driver, (transport)

for _ in range(5):
    t = bmp.temperature()                                # Read temperature, () → float C
    p = bmp.pressure()                                  # Read pressure, () → float hPa
    print('{} C, {} hPa'.format(t, p))
    machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')
