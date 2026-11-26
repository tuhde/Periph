from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.spl06 import SPL06Full
import time

connection = I2CConnection(0x77)                                         # Create I²C connection, (addr=0x77) → Connection
sensor = SPL06Full(connection)                                           # Create SPL06 driver, (connection) → None
sensor.init()                                                            # Check ID 0x10, read coefficients, apply defaults, () → None

# --- Identity and calibration ---
# init() already checked ID = 0x10; the nine coefficients are factory-trimmed
# per part and drive every compensated value below.
print('ID 0x10 verified')
print(sensor.coefficients())                                             # Decoded calibration, () → dict {c0 … c30}

# --- Altimeter: 20 s at 1 Hz, altitude relative to the first reading ---
# The first pressure becomes the reference level, so the altitude column shows
# how far the sensor moved up or down since start (about 12 Pa per metre near sea level).
p0 = sensor.read_pressure()                                              # Reference pressure, () → float Pa
for i in range(20):
    m = sensor.read()                                                    # Temperature and pressure, () → dict {temperature_c °C, pressure_pa Pa}
    t = sensor.read_temperature()                                        # Temperature only, () → float °C
    h = sensor.altitude(p0)                                              # Altitude vs. reference, (sea_level_pa) → float m
    print('%2d s  %.2f C (%.2f C)  %.2f Pa  %+.2f m' % (i, m['temperature_c'], t, m['pressure_pa'], h))
    time.sleep(1)

# --- Oversampling: noise and measurement time at 1x, 16x and 128x ---
# More oversampling means less noise but longer measurements; above 8x the
# driver sets P_SHIFT automatically.
for osr in (1, 16, 128):
    sensor.set_pressure_config(osr, 1)                                   # Pressure oversampling, (oversampling, rate) → None
    values = []
    start = time.time()
    for _ in range(10):
        values.append(sensor.read_pressure())                            # Pressure, () → float Pa
    elapsed = (time.time() - start) / 10
    mean = sum(values) / len(values)
    sd = (sum((v - mean) ** 2 for v in values) / len(values)) ** 0.5
    print('%3dx: mean %.2f Pa  sd %.2f Pa  %.1f ms per reading' % (osr, mean, sd, elapsed * 1000))

# --- Variometer: background mode at 8 Hz ---
# The chip measures on its own; read_continuous only fetches the latest
# results, so the host never waits for a conversion it started itself.
sensor.set_pressure_config(16, 8)                                        # 16x, 8 Hz, (oversampling, rate) → None
sensor.set_temperature_config(1, 8)                                      # 1x, 8 Hz, (oversampling, rate) → None
sensor.start_continuous(True, True)                                      # Background mode, (pressure, temperature) → None
last = None
for _ in range(16):
    c = sensor.read_continuous()                                         # Latest results, () → dict {temperature_c, pressure_pa}
    if last is not None:
        print('%.2f Pa  climb %+.2f m/s' % (c['pressure_pa'], -(c['pressure_pa'] - last) / 12.0 * 8))
    last = c['pressure_pa']
print('ready flags', sensor.data_ready())                                # Ready flags, () → (pressure_ready, temperature_ready)
sensor.stop()                                                            # Standby, () → None

# --- FIFO: let the chip collect 32 results, then read them in one go ---
# Each entry's LSB marks it as pressure or temperature; the host can sleep
# while the chip fills the FIFO (32 entries = 2 s at 8 Hz for both).
sensor.flush_fifo()                                                      # Empty the FIFO, () → None
sensor.enable_fifo()                                                     # FIFO on, () → None
sensor.start_continuous()                                                # Background mode, (pressure=True, temperature=True) → None
while not sensor.fifo_status()[1]:                                       # Wait for full, () → (empty, full)
    time.sleep(0.1)
sensor.stop()                                                            # Standby, () → None
entries = sensor.read_fifo()                                             # Drain FIFO, () → list of (kind, value)
pressures = [v for k, v in entries if k == 'pressure']
print('%d FIFO entries, %d pressure, mean %.2f Pa' % (len(entries), len(pressures),
                                                       sum(pressures) / max(len(pressures), 1)))
sensor.disable_fifo()                                                    # FIFO off, () → None

# --- Data-ready interrupt ---
# SDO doubles as the interrupt line; the callback receives the INT_STS bits.
# Without an INT pin on the connection the driver polls INT_STS instead.
sensor.enable_interrupt(SPL06Full.SOURCE_PRESSURE)                       # Pressure-ready interrupt, (source) → None
sensor.poll_interrupt()                                                  # Clear stale status, () → int
sensor.on_interrupt(lambda status: print('interrupt 0x%02X: %.2f Pa' % (status, sensor.read_continuous()['pressure_pa'])))  # Subscribe, (callback, int_pin=None) → None
sensor.start_continuous(True, True)                                      # Background mode, (pressure, temperature) → None
time.sleep(2)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
sensor.disable_interrupt(SPL06Full.SOURCE_PRESSURE)                      # Disable source, (source) → None
sensor.stop()                                                            # Standby, () → None

# --- Compensation by hand ---
# compensate() turns raw register values into SI units with the stored
# coefficients; useful for logging raw data and converting it later.
sensor.set_pressure_config(16, 1)                                        # Back to 16x, (oversampling, rate) → None
sensor.set_temperature_config(1, 1)                                      # Back to 1x, (oversampling, rate) → None
praw, traw = sensor.read_raw()                                           # Raw results, () → (praw, traw)
print('raw', praw, traw, '->', sensor.compensate(praw, traw, 16, 1))     # Compensate, (praw, traw, p_oversampling, t_oversampling) → dict

# --- Wrap up ---
# Standby, empty FIFO and a soft reset leave the chip as after power-up
# (plus the driver defaults).
sensor.stop()                                                            # Standby, () → None
sensor.flush_fifo()                                                      # Empty the FIFO, () → None
sensor.reset()                                                           # Soft reset and defaults, () → None
