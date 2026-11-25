from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ads7828 import ADS7828Full
import time

connection = I2CConnection(bus=1, addr=0x48)          # Create I2C connection, (bus, addr)
adc = ADS7828Full(connection)                         # Create ADS7828 driver, (connection, vref=None → internal 2.5 V)

DIVIDER = 2.0   # inputs sit behind 2:1 dividers (rails up to 5 V)

# --- Eight-channel supply monitor ---
# Scan CH0–CH7 once per second with the internal 2.5 V reference, print raw
# codes and rail voltages (after the divider ratio) and keep a running
# minimum/maximum per channel.
vmin = [99.0] * 8
vmax = [0.0] * 8
for n in range(5):
    raws = adc.read_scan(range(8))                   # Scan channels, (channels=list[int]) → list[int]
    volts = [r * adc.lsb() * DIVIDER for r in raws]  # Convert codes to rail voltages, V
    for ch in range(8):
        vmin[ch] = min(vmin[ch], volts[ch])
        vmax[ch] = max(vmax[ch], volts[ch])
    print('scan', n, ['{:4d}={:.3f}V'.format(r, v) for r, v in zip(raws, volts)])
    time.sleep(1)
print('min', ['{:.3f}'.format(v) for v in vmin])
print('max', ['{:.3f}'.format(v) for v in vmax])

# --- Read a bridge sensor on CH0/CH1 ---
# The signed differential read does two conversions (normal and inverted
# polarity) so the sign of the bridge output is preserved.
mv = adc.read_signed_differential(0) * 1000          # Read pair 0 signed, (pair=0–3) → float V
print('bridge {:+.2f} mV'.format(mv))

# --- Second scan against an external 5.0 V reference ---
# Switching to a 5 V external reference gives full-scale range up to VDD.
adc.set_reference(5.0)                               # Use external reference, (vref=None|float V) → None
raws = adc.read_scan(range(8))                       # Scan channels, (channels=list[int]) → list[int]
print('ext 5 V scan', raws)

# --- Finish in full power-down ---
adc.power_down()                                     # Power down completely, () → None
