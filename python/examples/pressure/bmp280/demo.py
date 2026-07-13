import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.pressure.bmp280 import BMP280Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)

# --- Weather monitoring preset: lowest power, forced mode ---
# BMP280 datasheet Table 7 setting: ×1/×1, filter off, forced mode.
# One sample per second for 30 seconds to characterise ambient conditions.
bmp = BMP280Full(transport)                              # Create BMP280 driver, (transport, bus_type='i2c')
bmp.configure(osrs_t=1, osrs_p=1, mode=1, filter=0, t_sb=0)  # Configure chip, (osrs_t=×1, osrs_p=×1, mode=forced, filter=off, t_sb=0) → None

temps, pressures, alts = [], [], []
for n in range(30):
    t = bmp.temperature()                                # Read temperature, () → float °C
    p = bmp.pressure()                                   # Read pressure, () → float hPa
    a = bmp.altitude()                                   # Compute altitude, (sea_level_hpa=1013.25) → float m
    temps.append(t)
    pressures.append(p)
    alts.append(a)
    print('{}s: {} C, {} hPa, alt={:.1f} m'.format(n, t, p, a))
    machine.sleep(1000)

print('Weather: T={:.1f}/{:.1f}/{:.1f} C, P={:.1f}/{:.1f}/{:.1f} hPa'.format(
    min(temps), sum(temps) / len(temps), max(temps),
    min(pressures), sum(pressures) / len(pressures), max(pressures)))

# --- Indoor navigation preset: high resolution with IIR filter ---
# ×16/×2 oversampling, filter coefficient 16, normal mode at ~26 Hz.
# The IIR filter suppresses sub-hPa noise; altitude resolution drops to cm level.
bmp.configure(osrs_t=2, osrs_p=5, mode=3, filter=4, t_sb=0)  # Configure chip, (osrs_t=×2, osrs_p=×16, mode=normal, filter=16, t_sb=0.5ms) → None

alts2 = []
for n in range(30):
    t = bmp.temperature()                                # Read temperature, () → float °C
    p = bmp.pressure()                                   # Read pressure, () → float hPa
    a = bmp.altitude()                                   # Compute altitude, (sea_level_hpa=1013.25) → float m
    alts2.append(a)
    print('{}s: alt={:.4f} m'.format(n, a))
    machine.sleep(1000)

mean_alt = sum(alts2) / len(alts2)
var = sum((x - mean_alt) ** 2 for x in alts2) / len(alts2)
std = var ** 0.5
print('Navigation: alt min={:.4f} max={:.4f} std={:.4f} m'.format(min(alts2), max(alts2), std))
print('===DONE: 0 passed, 0 failed===')
