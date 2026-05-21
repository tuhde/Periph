import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.pressure.bmp280 import BMP280Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, 0x76)

chip = BMP280Full(transport)                               # Create BMP280 full driver, (transport, osrs_t=1, osrs_p=1)

# --- Weather monitoring preset (first 30 s) ---
# Ultra-low power: forced mode, ×1/×1, filter off.
# Demonstrates the chip's baseline noise floor.
print('=== Weather monitoring (forced ×1/×1, filter off) ===')
t0 = chip.temperature()                                    # Read temperature, () → float °C
p0 = chip.pressure()                                        # Read pressure, () → float hPa
alt0 = chip.altitude()                                      # Compute altitude, (sea_level_hpa=1013.25) → float m
print('Start: T={:.2f}C  P={:.2f}hPa  alt={:.2f}m'.format(t0, p0, alt0))
machine.sleep(500)

altitudes_w = []
for n in range(10):
    t = chip.temperature()                                  # Read temperature, () → float °C
    p = chip.pressure()                                      # Read pressure, () → float hPa
    a = chip.altitude()                                      # Compute altitude, (sea_level_hpa=1013.25) → float m
    altitudes_w.append(a)
    print('  {:2d}s: T={:.2f}C  P={:.2f}hPa  alt={:.3f}m'.format(n+1, t, p, a))
    machine.sleep(500)

avg_w = sum(altitudes_w) / len(altitudes_w)
print('Weather avg altitude: {:.3f}m'.format(avg_w))

# --- Switch to indoor navigation preset (normal mode ×16/×2, filter 16) ---
print('=== Indoor navigation (normal ×16/×2, filter 16, t_sb=250ms) ===')
chip.configure(osrs_t=5, osrs_p=2, mode=BMP280Full.MODE_NORMAL,
               filter=BMP280Full.FILTER_16, t_sb=BMP280Full.T_SB_250_MS)
machine.sleep(1000)  # let IIR filter settle

t1 = chip.temperature()                                    # Read temperature, () → float °C
p1 = chip.pressure()                                        # Read pressure, () → float hPa
a1 = chip.altitude()                                        # Compute altitude, (sea_level_hpa=1013.25) → float m
print('Navigation start: T={:.2f}C  P={:.2f}hPa  alt={:.3f}m'.format(t1, p1, a1))

altitudes_n = []
for n in range(10):
    t = chip.temperature()                                  # Read temperature, () → float °C
    p = chip.pressure()                                      # Read pressure, () → float hPa
    a = chip.altitude()                                      # Compute altitude, (sea_level_hpa=1013.25) → float m
    altitudes_n.append(a)
    print('  {:2d}s: T={:.4f}C  P={:.2f}hPa  alt={:.4f}m'.format(n+1, t, p, a))
    machine.sleep(500)

avg_n = sum(altitudes_n) / len(altitudes_n)
print('Navigation avg altitude: {:.4f}m'.format(avg_n))

# --- Filter comparison ---
std_w = (sum((a - avg_w) ** 2 for a in altitudes_w) / len(altitudes_w)) ** 0.5
std_n = (sum((a - avg_n) ** 2 for a in altitudes_n) / len(altitudes_n)) ** 0.5
print('=== Filter comparison: no-filter std={:.3f}m  filter-16 std={:.4f}m ==='.format(std_w, std_n))
print('===DONE: 0 passed, 0 failed===')