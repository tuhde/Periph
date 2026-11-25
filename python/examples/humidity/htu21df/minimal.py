from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.htu21df import HTU21DFMinimal
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = HTU21DFMinimal(connection)                                      # Create HTU21D-F driver, (connection) → None
sensor.init()                                                            # Soft reset, () → None

while True:
    t, rh = sensor.read()                                                # Read temperature and humidity, () → (float °C, float %RH)
    print('%.2f C, %.1f %%RH' % (t, rh))
    time.sleep(2)
