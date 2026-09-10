from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.bmp581 import BMP581Full

connection = I2CConnection(0x46)

# --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
# 10 Hz ODR (odr field 0x17) gives sub-decimetre altitude resolution over
# a 30-second window while still leaving headroom for higher OSR.
bmp = BMP581Full(connection)                             # Create BMP581 driver, (connection, bus_type='i2c')
bmp.configure(odr=0x17, osr_p=4, osr_t=2, press_en=True)  # Configure chip, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → None

pressures, temps, alts = [], [], []
for n in range(300):
    p = bmp.pressure()                                   # Read pressure, () → float Pa
    t = bmp.temperature()                                # Read temperature, () → float °C
    a = bmp.altitude()                                   # Compute altitude, (sea_level_pa=101325.0) → float m
    if n % 10 == 0:
        mp = sum(pressures[-10:]) / max(1, min(10, len(pressures)))
        mt = sum(temps[-10:]) / max(1, min(10, len(temps)))
        ma = sum(alts[-10:]) / max(1, min(10, len(alts)))
        print('{}s: rolling P={:.1f} Pa, T={:.2f} C, alt={:.2f} m'.format(n / 10, mp, mt, ma))
    pressures.append(p)
    temps.append(t)
    alts.append(a)
    machine.sleep(100)

# --- Compare IIR bypass vs IIR coefficient 3 noise floor ---
# Coefficient 3 = 7-tap filter; expect noticeably tighter altitude variance.
print('Bypass: alt min={:.3f} max={:.3f} spread={:.3f} m'.format(min(alts), max(alts), max(alts) - min(alts)))

bmp.set_iir_filter(BMP581Full.IIR_COEFF_3, BMP581Full.IIR_BYPASS)  # Set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → None

pressures2, alts2 = [], []
for n in range(300):
    p = bmp.pressure()                                   # Read pressure, () → float Pa
    a = bmp.altitude()                                   # Compute altitude, (sea_level_pa=101325.0) → float m
    pressures2.append(p)
    alts2.append(a)
    machine.sleep(100)

print('IIR=3:  alt min={:.3f} max={:.3f} spread={:.3f} m'.format(min(alts2), max(alts2), max(alts2) - min(alts2)))
print('Min P={:.1f}, max P={:.1f}, mean P={:.1f} Pa'.format(min(pressures), max(pressures), sum(pressures) / len(pressures)))
print('===DONE: 0 passed, 0 failed===')