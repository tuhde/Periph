"""BMP280 demo — weather vs altimeter switching.

Weather monitoring (forced mode, ×1/×1, filter off) for 30 s,
then indoor navigation (normal mode, ×16/×2, filter coeff 16)
for 30 s. Demonstrates noise floor reduction via the IIR filter.
"""
from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.pressure.bmp280 import BMP280Full
import time

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)            # Create I2C bus, (id, sda, scl, freq=400000 Hz)
transport = I2CTransport(i2c, 0x76)                            # Create I2C transport, (i2c, addr=0x76)
chip = BMP280Full(transport)                                    # Create BMP280 full driver, (transport, addr=0x76)

# --- Phase 1: weather monitoring (forced mode, ×1/×1, filter off) ---
chip.configure(mode=BMP280Full.MODE_FORCED, osrs_t=1, osrs_p=1, filter=0)
print("=== Weather monitoring (forced ×1/×1, filter off) ===")
for i in range(10):
    t = chip.temperature()
    p = chip.pressure()
    alt = chip.altitude()
    print("  T={:.2f}°C  P={:.2f}hPa  alt={:.1f}m".format(t, p, alt))
    time.sleep(1)

# --- Phase 2: indoor navigation (normal mode, ×16/×2, filter coeff 16, 0.5 ms t_sb → ~26 Hz) ---
chip.configure(mode=BMP280Full.MODE_NORMAL, osrs_t=5, osrs_p=5, filter=4, t_sb=4)
print("=== Indoor navigation (normal ×16/×2, filter coeff 16) ===")
altitudes = []
for i in range(10):
    t = chip.temperature()
    p = chip.pressure()
    alt = chip.altitude(sea_level_hpa=1013.25)
    altitudes.append(alt)
    print("  T={:.2f}°C  P={:.2f}hPa  alt={:.4f}m".format(t, p, alt))
    time.sleep(1)

min_alt = min(altitudes)
max_alt = max(altitudes)
mean_alt = sum(altitudes) / len(altitudes)
print("altitude stats: min={:.2f}m  max={:.2f}m  mean={:.2f}m  range={:.2f}m".format(
    min_alt, max_alt, mean_alt, max_alt - min_alt))