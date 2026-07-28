from periph.connection.i2c_auto import I2CConnection
from periph.chips.comms.rda5807m import RDA5807MMinimal
import time

connection = I2CConnection(0x10)
fm = RDA5807MMinimal(connection, frequency_mhz=100.0, volume=8)   # Create RDA5807M driver, (connection, frequency_mhz=100.0, volume=8)

while True:
    freq = fm.seek(up=True)                             # Seek to next station, (up=True) → float or None
    if freq is not None:
        print(freq, "MHz")
    time.sleep(3)
