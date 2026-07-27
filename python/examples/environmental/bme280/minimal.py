from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.bme280 import BME280Minimal

connection = I2CConnection(0x76)
bme = BME280Minimal(connection)                         # Create BME280 driver, (connection, bus_type='i2c')

for _ in range(5):
    t = bme.temperature()                              # Read temperature, () → float °C
    p = bme.pressure()                                 # Read pressure, () → float hPa
    h = bme.humidity()                                 # Read humidity, () → float %RH
    print('{} C, {} hPa, {} %RH'.format(t, p, h))
    machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')
