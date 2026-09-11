from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.lps33hw import LPS33HWMinimal

connection = I2CConnection(0x5C)
lps = LPS33HWMinimal(connection)                       # Create LPS33HW driver, (connection)

for _ in range(5):
    t = lps.temperature()                              # Read temperature, () → float °C
    p = lps.pressure()                                 # Read pressure, () → float Pa
    print('{} C, {} Pa'.format(t, p))
    machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')