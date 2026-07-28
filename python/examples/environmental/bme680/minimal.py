from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.bme680 import BME680Minimal

connection = I2CConnection(0x76)              # Open I²C connection, (i2c, addr)
bme = BME680Minimal(connection)                           # Create BME680 driver, (connection)

for _ in range(5):
    t = bme.temperature()                                # Read temperature, () → float °C
    p = bme.pressure()                                   # Read pressure, () → float hPa
    h = bme.humidity()                                   # Read humidity, () → float %RH
    g = bme.gas_resistance()                             # Read gas resistance, () → float Ω
    print('{} C, {} hPa, {} %RH, {} Ohm'.format(t, p, h, g))
    machine.sleep(5000)
print('===DONE: 0 passed, 0 failed===')
