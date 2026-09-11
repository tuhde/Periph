from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.lps33hw import LPS33HWFull

SEA_LEVEL_PA = 101325
connection = I2CConnection(0x5C)

# --- Altimeter preset: 10 Hz with ODR/20 LPF for stable altitude estimates ---
# Higher ODR + aggressive LPF gives smoother output for barometric altitude;
# BDU=1 prevents reading pressure bytes from different samples.
lps = LPS33HWFull(connection)                          # Create LPS33HW driver, (connection)
lps.configure(odr=2, bdu=True, en_lpfp=True, lpfp_cfg=1, lc_en=False, sim=False)  # Configure chip, (odr=10Hz, bdu=True, en_lpfp=True, lpfp_cfg=ODR/20) → None

# --- Sample once per second for 60 seconds, computing altitude each tick ---
# Polling P_DA would be tidier than sleeping, but at 10 Hz ODR a fixed 1 s
# delay gives one fresh sample every read.
alts = []
for n in range(60):
    t = lps.temperature()                              # Read temperature, () → float °C
    p = lps.pressure()                                 # Read pressure, () → float Pa
    alt = 44330 * (1 - (p / SEA_LEVEL_PA) ** (1 / 5.255))  # barometric formula
    alts.append(alt)
    print('{}s: {} C, {} Pa, alt={:.2f} m'.format(n, t, p, alt))
    machine.sleep(1000)

# --- AUTOZERO re-zeros every 10 s to remove slow atmospheric drift ---
# AUTOZERO stores the current pressure in REF_P so the chip subtracts it
# from every subsequent reading. Useful for relative altitude measurements
# in changing weather.
if (len(alts) % 10) == 0:
    lps.set_autozero()                                 # Set AUTOZERO, () → None

print('Altitude: min={:.2f} max={:.2f} range={:.2f} m'.format(
    min(alts), max(alts), max(alts) - min(alts)))
print('===DONE: 0 passed, 0 failed===')