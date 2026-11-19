import sys
import time

from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hs30xx import HS30xxFull

connection = I2CConnection(0x44)                                         # Create I²C connection, (addr=0x44) → Connection
sensor = HS30xxFull(connection)                                          # Create HS30xx driver, (connection) → None


def power_cycle():
    # Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
    time.sleep(0.1)


# --- Optional: show and change the stored resolution (run with --resolution) ---
# The NVM is only reachable within 10 ms after power-up, so this needs power_cycle().
if '--resolution' in sys.argv:
    print('resolution', sensor.get_resolution(power_cycle))              # Get NVM resolution, (power_cycle=None) → (int, int)
    sensor.set_resolution(12, 12, power_cycle)                           # Set NVM resolution, (humidity_bits, temperature_bits, power_cycle=None) → None
    sensor.set_measurement_wait(15)                                      # 12-bit RH + T need about 10 ms, (ms) → None
    print('resolution', sensor.get_resolution(power_cycle))

# --- Hygrometer: one reading every 2 s for 30 s ---
# Raw values and status come from the 14-bit data-fetch frame; status 0 means fresh data.
print('%-6s %-8s %-8s %-6s %-9s %-9s' % ('t (s)', 'RH raw', 'T raw', 'status', 'T (C)', 'Dew (C)'))
for n in range(15):
    rh_raw, t_raw, status = sensor.read_raw()                            # Read raw values, () → (int, int, int)
    d = sensor.read()                                                    # Read temperature and humidity, () → dict {temperature_c, humidity_pct}
    dew = sensor.dew_point()                                             # Dew point, () → float °C
    print('%-6d %-8d %-8d %-6d %-9.2f %-9.2f   %.1f %%RH' % (
        n * 2, rh_raw, t_raw, status, d['temperature_c'], dew, d['humidity_pct']))
    time.sleep(2)
