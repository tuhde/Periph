from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.ms5611 import MS5611Full
import time

connection = I2CConnection(0x77)                                         # Create I²C connection, (addr=0x77) → Connection
sensor = MS5611Full(connection)                                          # Create MS5611 driver, (connection) → None
sensor.init()                                                            # Reset, read PROM, verify CRC-4, () → None

# --- Calibration data ---
# Six coefficients and a CRC-4 are factory-programmed into the PROM.
print('PROM', [hex(w) for w in sensor.read_prom()])                      # Read the PROM words, () → list of 8 int
print('CRC ok', sensor.prom_crc_ok())                                    # Verify the PROM CRC-4, () → bool
print('coefficients', sensor.coefficients())                             # Calibration coefficients, () → (c1 .. c6)
print('serial', sensor.serial_code())                                    # Serial code, () → int

# --- Barometric altimeter / variometer: 20 s, one reading per second ---
reference = sensor.read_pressure()                                       # Read pressure only, () → float Pa
previous = None
lowest = 100.0
for _ in range(20):
    m = sensor.read()                                                    # Read temperature and pressure, () → dict {temperature_c °C, pressure_pa Pa}
    lowest = min(lowest, m['temperature_c'])
    height = sensor.altitude(reference)                                  # Altitude from pressure, (sea_level_pa) → float m
    climb = 0.0 if previous is None else height - previous
    previous = height
    print('%.2f C  %.1f Pa  %.2f m  %+.2f m/s' % (m['temperature_c'], m['pressure_pa'], height, climb))
    time.sleep(1)

# --- Noise and time versus oversampling: ten readings each ---
for osr in (256, 512, 1024, 2048, 4096):
    sensor.set_oversampling(osr)                                         # Select oversampling ratio, (osr) → None
    start = time.time()
    values = [sensor.read_pressure() for _ in range(10)]
    elapsed = (time.time() - start) * 1000.0 / 10
    mean = sum(values) / len(values)
    spread = max(values) - min(values)
    print('OSR %4d  %.1f ms/read  spread %.1f Pa' % (osr, elapsed, spread))
sensor.set_oversampling(4096)

# --- Raw codes with and without the second-order correction ---
d1, d2 = sensor.read_raw()                                               # Read raw ADC codes, () → (int, int)
print('raw', d1, d2)
print('first order ', sensor.compensate(d1, d2, second_order=False))     # Compensate raw codes, (d1, d2, second_order) → dict
print('second order', sensor.compensate(d1, d2))
print('lowest temperature seen %.2f C' % lowest)

# --- Non-blocking conversion ---
sensor.start_conversion(MS5611Full.PRESSURE)                             # Start a conversion, (kind, osr=None) → None
time.sleep(sensor.conversion_time() + 0.001)                             # Max conversion time, (osr=None) → float s
print('D1', sensor.read_adc())                                           # Read the ADC result, () → int

# --- Finish with a reset ---
sensor.reset()                                                           # Reset and reload PROM, () → None
