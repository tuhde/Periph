from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.bmp384 import BMP384Full

connection = I2CConnection(0x76)
bmp = BMP384Full(connection)                             # Create BMP384 driver, (connection, bus_type='i2c')

# --- Configure for noise-sensitive altitude logging ---
# osr_p=×16 gives ~12 cm noise-equivalent altitude resolution; the IIR
# coefficient 3 suppresses door-slam / gust spikes without too much step lag.
# ODR=25 Hz gives us a sample every 40 ms, well above the ~38 ms T_conv.
bmp.configure(osr_p=4, osr_t=1, iir_filter=2, odr_sel=0x03)  # Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
bmp.set_mode(BMP384Full.MODE_NORMAL)                    # Set power mode, (mode 0/1/3) → None

# --- Sample for 30 seconds, logging altitude every 500 ms ---
# P0 = 1013.25 hPa (ISA sea-level reference). 30 s × 2 Hz = 60 rows.
SEA_LEVEL_HPA = 1013.25
import time
start = time.ticks_ms()
rows = []
while time.ticks_diff(time.ticks_ms(), start) < 30000:
    t = bmp.temperature()                               # Read temperature, () → float °C
    p = bmp.pressure()                                  # Read pressure, () → float hPa
    altitude = 44330.0 * (1.0 - (p / SEA_LEVEL_HPA) ** (1.0 / 5.255))
    elapsed = time.ticks_diff(time.ticks_ms(), start) / 1000.0
    rows.append((elapsed, p, t, altitude))
    print('{:.1f}s  {:7.2f} hPa  {:5.1f} C  {:6.1f} m'.format(*rows[-1]))
    time.sleep_ms(500)

print('Sampled {} rows over 30 s'.format(len(rows)))
print('===DONE: 0 passed, 0 failed===')
