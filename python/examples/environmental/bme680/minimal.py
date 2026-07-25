from periph.transport.i2c_auto import I2CTransport
from periph.chips.environmental.bme680 import BME680Minimal

transport = I2CTransport(0x76)              # Open I²C transport, (i2c, addr)
bme = BME680Minimal(transport)                           # Create BME680 driver, (transport)

for _ in range(5):
    t = bme.temperature()                                # Read temperature, () → float °C
    p = bme.pressure()                                   # Read pressure, () → float hPa
    h = bme.humidity()                                   # Read humidity, () → float %RH
    g = bme.gas_resistance()                             # Read gas resistance, () → float Ω
    print('{} C, {} hPa, {} %RH, {} Ohm'.format(t, p, h, g))
    machine.sleep(5000)
print('===DONE: 0 passed, 0 failed===')
