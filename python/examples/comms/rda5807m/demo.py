from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.comms.rda5807m import RDA5807MFull
import time

i2c = I2C(0, freq=400000)
transport = I2CTransport(i2c, 0x10)
fm = RDA5807MFull(transport, frequency_mhz=87.5, volume=10)

# --- FM band scanner ---
# Start at the bottom of the world-wide band and repeatedly seek upward with
# SKMODE=1 (stop at band limit, the Minimal/Full default) so a seek that
# returns None means the top of the band has been reached and the scan is done.
fm.enable_rds(True)                                  # Enable RDS/RBDS, (enable) → None

stations = []
while True:
    freq = fm.seek(up=True)                          # Seek next station, (up=True) → float or None
    if freq is None:
        break
    if not fm.is_station():                          # Check real station, () → bool
        continue

    rssi = fm.signal_strength()                      # Read RSSI, () → int 0–127
    stereo = 'stereo' if fm.is_stereo() else 'mono'   # Check stereo indicator, () → bool
    name = None

    # --- Try to read the Program Service (station) name via RDS ---
    # Group types 0A/0B carry the 8-character PS name, four segments of two
    # characters each, addressed by block B bits 1:0. Give the decoder up to
    # 2 seconds to assemble a full name before moving on to the next station.
    ps_chars = [None] * 8
    deadline = time.ticks_add(time.ticks_ms(), 2000)
    while time.ticks_diff(deadline, time.ticks_ms()) > 0:
        if fm.rds_ready():                            # Check RDS group ready, () → bool
            group = fm.read_rds_group()               # Read raw RDS blocks, () → tuple or None
            if group is not None:
                block_a, block_b, block_c, block_d = group
                group_type = block_b >> 12
                is_b_variant = (block_b >> 11) & 1
                if group_type == 0 and is_b_variant == 0:
                    segment = block_b & 0x03
                    ps_chars[segment * 2] = chr(block_d >> 8)
                    ps_chars[segment * 2 + 1] = chr(block_d & 0xFF)
                    if None not in ps_chars:
                        name = ''.join(ps_chars)
                        break
        time.sleep_ms(40)

    label = name.strip() if name else '(no RDS name)'
    print('{:6.2f} MHz  RSSI={:3d}  {}  {}'.format(freq, rssi, stereo, label))
    stations.append((freq, rssi, stereo, label))

print()
print('Scan complete: {} station(s) found'.format(len(stations)))
