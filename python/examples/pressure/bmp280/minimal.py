from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.pressure.bmp280 import BMP280Minimal
import time

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)            # Create I2C bus, (id, sda, scl, freq=400000 Hz)
transport = I2CTransport(i2c, 0x76)                            # Create I2C transport, (i2c, addr=0x76)
chip = BMP280Minimal(transport)                               # Create BMP280 driver, (transport, addr=0x76)

while True:
    t = chip.temperature()                                     # Read temperature, () → float °C
    p = chip.pressure()                                       # Read pressure, () → float hPa
    print("T={:.2f}°C  P={:.2f}hPa".format(t, p))
    time.sleep(1)