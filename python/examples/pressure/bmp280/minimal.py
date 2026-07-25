from periph.transport.i2c_auto import I2CTransport
from periph.chips.pressure.bmp280 import BMP280Minimal

transport = I2CTransport(0x76)
bmp = BMP280Minimal(transport)                           # Create BMP280 driver, (transport, bus_type='i2c')

for _ in range(5):
    t = bmp.temperature()                                # Read temperature, () → float °C
    p = bmp.pressure()                                   # Read pressure, () → float hPa
    print('{} C, {} hPa'.format(t, p))
    machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')
