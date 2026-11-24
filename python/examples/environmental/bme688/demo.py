import math
import time

from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.bme688 import BME688Full

connection = I2CConnection(0x76)              # Open I²C connection, (i2c, addr)

# --- Room air probe with parallel mode ---
# Part 1: ten forced-mode readings (T, RH, hPa, ohm, dew point), one per 5 s.
# Part 2: parallel mode with a 3-step heater profile (320, 250 and 180 degC;
# repeats 1, 2, 1; shared wait 100 ms) for one minute, printing the gas
# resistance of every heater step, then back to sleep.
bme = BME688Full(connection)                              # Create BME688 driver, (connection)
bme.configure(osrs_t=2, osrs_p=5, osrs_h=1, mode=0, filter=4)  # Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=sleep, filter=15) → None
bme.set_heater(320, 150)                                 # Configure heater profile 0, (temp_c=320, duration_ms=150) → None


def dew_point(t, rh):
    # Magnus formula, SI units (degC, %RH)
    g = math.log(max(rh, 0.01) / 100.0) + 17.62 * t / (243.12 + t)
    return 243.12 * g / (17.62 - g)


print('--- forced mode ---')
for n in range(10):
    t, p, h, g = bme.read_all()                          # Read all sensors in one cycle, () → tuple
    print('{}: {:.1f} C, {:.1f} %RH, {:.1f} hPa, {:.0f} Ohm, dew {:.1f} C'.format(n, t, h, p, g, dew_point(t, h)))
    time.sleep(5)

print('--- parallel mode ---')
bme.start_parallel([(320, 1), (250, 2), (180, 1)], 100)  # Start parallel mode, (steps=[(temp_c, repeats)], shared_wait_ms=100) → None
for n in range(60):
    for f in bme.read_parallel():                        # Read the new data fields, () → list of dict
        print('step {} #{}: {:.1f} C, {:.1f} %RH, {:.0f} Ohm (valid={}, stable={})'.format(
            f['step'], f['sub_index'], f['temperature'], f['humidity'], f['gas_resistance'],
            f['gas_valid'], f['heater_stable']))
    time.sleep(1)
bme.stop_parallel()                                      # Back to sleep, () → None
print('===DONE: 0 passed, 0 failed===')
