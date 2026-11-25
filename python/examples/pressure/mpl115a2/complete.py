from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.mpl115a2 import MPL115A2Full

connection = I2CConnection(0x60)
baro = MPL115A2Full(connection)                         # Create MPL115A2 driver, (connection)
a0, b1, b2, c12 = baro.coefficients()                   # Read cached coefficients, () → (a0, b1, b2, c12) floats
baro.reload_coefficients()                              # Re-read coefficient block, () → None
                                                        # use after a power cycle
data = baro.read()                                      # One conversion, () → dict {pressure_pa, temperature_c}
padc, tadc = baro.read_raw()                            # One conversion, () → (padc, tadc) 10-bit counts
p = baro.compensate(padc, tadc)                         # Compensate raw counts, (padc, tadc) → float Pa
                                                        # pure function, no bus access
hpa = baro.pressure_hpa()                               # Read pressure, () → float hPa
alt = baro.altitude()                                   # Compute altitude, (sea_level_pa=101325.0) → float m
                                                        # barometric formula, informative
print('coef', a0, b1, b2, c12)
print(data, padc, tadc, p, hpa, alt)
print('===DONE: 0 passed, 0 failed===')
