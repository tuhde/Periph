from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.mpl115a2 import MPL115A2Full

connection = I2CConnection(0x60)
baro = MPL115A2Full(connection)                         # Create MPL115A2 driver, (connection)
print('Coefficients:', baro.coefficients())             # Read cached coefficients, () → (a0, b1, b2, c12)

# --- Barometer log ---
# Reads the pressure every 2 s for 30 s, printing Pa, hPa, approximate
# altitude and the raw counts, then the min/max of the run.
values = []
for n in range(15):
    pa = baro.read_pressure()                           # Read pressure, () → float Pa
    hpa = baro.pressure_hpa()                           # Read pressure, () → float hPa
    alt = baro.altitude()                               # Compute altitude, (sea_level_pa=101325.0) → float m
    padc, tadc = baro.read_raw()                        # One conversion, () → (padc, tadc)
    values.append(pa)
    print('{}s: {:.0f} Pa, {:.2f} hPa, alt={:.1f} m, raw=({}, {})'.format(n * 2, pa, hpa, alt, padc, tadc))
    machine.sleep(2000)

print('Summary: min={:.0f} Pa, max={:.0f} Pa'.format(min(values), max(values)))
print('===DONE: 0 passed, 0 failed===')
