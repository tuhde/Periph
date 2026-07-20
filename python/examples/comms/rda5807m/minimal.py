from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.comms.rda5807m import RDA5807MMinimal
import time

i2c = I2C(0, freq=400000)
transport = I2CTransport(i2c, 0x10)
fm = RDA5807MMinimal(transport, frequency_mhz=100.0, volume=8)   # Create RDA5807M driver, (transport, frequency_mhz=100.0, volume=8)

while True:
    freq = fm.seek(up=True)                             # Seek to next station, (up=True) → float or None
    if freq is not None:
        print(freq, "MHz")
    time.sleep(3)
