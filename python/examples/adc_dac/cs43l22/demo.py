import time

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.cs43l22 import CS43L22Full

# --- Bring up the headphone amplifier ---
# The control port only configures the codec; MCLK = 12.288 MHz and the
# 48 kHz I2S stream come from the board's I2S master. The constructor loads
# the I2S-slave defaults and the required initialization block while the chip
# is still powered down; start() powers it up once the clocks are running.
connection = I2CConnection(bus=1, addr=0x4A)                 # Create I2C connection, (bus=1, addr=0x4A)
dac = CS43L22Full(connection)                                # Create CS43L22 driver, (connection, res_pin=None)
print(dac.read_id())                                         # Read chip ID and revision, () → (int, int)
dac.set_volume(-60.0)                                        # Set master volume, (db=-102–12 dB) → None
dac.start()                                                  # Power up the DAC once MCLK is running, () → None

# --- Wait for the serial port to lock ---
# SPCLKERR reads 1 until LRCK is valid and the MCLK/LRCK ratio is measured;
# reading the status register clears the flags.
for _ in range(50):
    if not dac.read_status()['clock_error']:                 # Read status flags (clears them), () → dict
        break
    time.sleep(0.1)

# --- Ramp the volume up ---
# 2 dB steps every 100 ms avoid an abrupt start; the digital soft ramp
# smooths each step further.
for vol in range(-60, 1, 2):
    dac.set_volume(float(vol))                               # Set master volume, (db=-102–12 dB) → None
    time.sleep(0.1)

# --- Shape the tone and protect the headphones ---
# +6 dB bass and treble shelves add presence; the limiter at -6 dB stops
# loud peaks from clipping the amplifier.
dac.set_tone(6.0, 6.0)                                       # Configure treble / bass shelves, (treble_db, bass_db, treble_corner=0, bass_corner=0, enabled=True) → None
dac.set_limiter(True, -6, -12)                               # Configure the peak limiter, (enabled, max_db=0, cushion_db=0, release=0x3F, attack=0, limit_all=True, soft_ramp_disable=False, zero_cross_disable=False) → None

# --- Play a single beep ---
# The beep generator needs no audio data; it mixes into the output once
# MCLK and LRCK are running. Frequency code 0x1 is 521.74 Hz; 0xF is
# the highest tone (2181.82 Hz).
dac.set_beep(0x1, 2, 0, -6.0, CS43L22Full.BEEP_SINGLE)       # Play a beep, (frequency 0–15, on_time 0–15, off_time 0–7, volume_db, mode, mix=True) → None
time.sleep(1.5)
dac.stop_beep()                                              # Stop the beep generator, () → None

# --- Power down safely ---
# stop() mutes, disables ramps and waits 100 us after POWER_CTL_1 = 0x9F;
# only then may the application remove MCLK.
dac.stop()                                                   # Power down the DAC, () → None
print('MCLK may now be removed')
