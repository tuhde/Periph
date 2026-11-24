from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.bh1750 import BH1750Full
import time

connection = I2CConnection(0x23)                                         # Create I²C connection, (addr=0x23) → Connection
sensor = BH1750Full(connection)                                          # Create BH1750 driver, (connection) → None
sensor.init()                                                            # Power on, () → None

# --- Light meter demo ---------------------------------------------------
# One-time measurement in each mode
for name, mode in (('H (1 lx)', sensor.MODE_H), ('H2 (0.5 lx)', sensor.MODE_H2), ('L (4 lx)', sensor.MODE_L)):
    t0 = time.ticks_ms() if hasattr(time, 'ticks_ms') else int(time.time() * 1000)
    lux = sensor.measure_once(mode)                                      # One-time measurement, (mode) → float lx
    t1 = time.ticks_ms() if hasattr(time, 'ticks_ms') else int(time.time() * 1000)
    print('%-12s %9.2f lx  (%d ms)' % (name, lux, t1 - t0))

# Continuous mode, ten values per second for 5 s: cover and uncover the sensor
sensor.start_continuous(sensor.MODE_H)                                   # Start continuous mode, (mode) → None
for _ in range(50):
    time.sleep(0.1)
    print('%.1f lx' % sensor.read_continuous())                          # Latest result, () → float lx

# Sensitivity: constant light, lowest and highest sensitivity
for factor in (0.45, 3.68):
    sensor.set_sensitivity(factor)                                       # Set sensitivity, (factor) → None
    sensor.measure_once(sensor.MODE_H)
    print('x%.2f: MTreg %d, raw %d, %.2f lx, max %.0f ms' % (
        factor, sensor.get_measurement_time_register(), sensor.read_raw(),
        sensor.measure_once(sensor.MODE_H), sensor.measurement_time() * 1000))
sensor.set_sensitivity(1.0)

# Reset and power cycle
sensor.reset()                                                           # Clear the data register, () → None
sensor.power_down()                                                      # Power down, () → None
sensor.power_on()                                                        # Power on, () → None

# Log-scale bar graph
import math
while True:
    lux = sensor.measure_once(sensor.MODE_H)
    bar = int(max(0.0, math.log10(max(lux, 1.0))) * 8)                   # 0 lx ... 100 000 lx -> 40 characters
    print('%8.1f lx |%s' % (lux, '#' * bar))
    time.sleep(0.5)
