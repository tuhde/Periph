from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.aht21 import AHT21Full
import time
import math

connection = I2CConnection(0x38)                                         # Create I²C connection, (addr=0x38) → Connection
aht = AHT21Full(connection)                                             # Create AHT21 driver, (connection) → None

# --- Verify calibration before starting the logging session ---
# Most AHT21 modules ship pre-calibrated; if the CAL bit is not set
# the driver already sent the calibration init sequence during __init__.
print('Calibrated:', aht.is_calibrated())                              # Check calibration status, () → bool

print('%-8s %-10s %-10s %-10s' % ('Time', 'T (C)', 'RH (%)', 'Dew (C)'))
for n in range(60):
    # --- Each reading requires an 80 ms measurement cycle ---
    # The sensor cannot output data faster than this; the driver
    # handles the trigger + wait internally.
    rc = aht.read_with_crc()                                           # Read with CRC verification, () → dict {temperature_c, humidity_pct, crc_ok}
    if not rc['crc_ok']:
        print('CRC error at sample', n)
        continue

    t = rc['temperature_c']
    rh = rc['humidity_pct']

    # --- Magnus formula dew-point approximation ---
    # gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
    # dew_point = (243.04 * gamma) / (17.625 - gamma)
    # Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
    gamma = math.log(rh / 100.0) + (17.625 * t) / (243.04 + t)
    dew = (243.04 * gamma) / (17.625 - gamma)

    print('%-8d %-10.2f %-10.2f %-10.2f' % (n, t, rh, dew))
    time.sleep(5)
