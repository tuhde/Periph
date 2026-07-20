from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.comms.rda5807m import RDA5807MFull
import time

i2c = I2C(0, freq=400000)
transport = I2CTransport(i2c, 0x10)
fm = RDA5807MFull(transport, frequency_mhz=100.0, volume=8)   # Create RDA5807M driver, (transport, frequency_mhz=100.0, volume=8)
                                                     # runs the init sequence and tunes to the initial frequency

fm.set_frequency(97.5)                               # Tune to frequency, (frequency_mhz) → None
                                                     # computes CHAN from the current band/spacing and blocks until STC
print(fm.frequency())                                # Read tuned frequency, () → float MHz
                                                     # converts READCHAN back to MHz

fm.set_volume(10)                                    # Set volume, (level 0–15) → None
fm.mute(False)                                       # Mute/unmute, (enable) → None
                                                     # enable=True mutes; here we ensure audio is audible

freq = fm.seek(up=True)                              # Seek next station, (up=True) → float or None
                                                     # blocks until STC; returns None if SF (seek fail) is set
print(freq)

fm.configure(band=RDA5807MFull.BAND_WORLD, space=RDA5807MFull.SPACE_100K,
             de_emphasis=True, seek_threshold=8, seek_mode=True)
                                                     # Configure tuner, (band, space, de_emphasis, seek_threshold, seek_mode, clk_mode, afc_disable, east_europe_50m) → None
                                                     # re-tunes to the current frequency if band or space changed

fm.set_bass_boost(True)                              # Enable bass boost, (enable) → None
fm.set_mono(False)                                   # Force mono/allow stereo, (enable) → None
fm.set_softmute(True)                                # Enable soft mute, (enable) → None

fm.enable_rds(True)                                  # Enable RDS/RBDS, (enable) → None
time.sleep(1)
print(fm.rds_ready())                                # Check RDS group ready, () → bool
print(fm.read_rds_group())                           # Read raw RDS blocks, () → tuple or None

print(fm.is_stereo())                                # Check stereo indicator, () → bool
print(fm.is_station())                               # Check real station, () → bool
print(fm.is_ready())                                 # Check tuner ready, () → bool
print(fm.signal_strength())                          # Read RSSI, () → int 0–127

fm.standby(True)                                     # Power down/up, (enable) → None
time.sleep_ms(10)
fm.standby(False)

fm.soft_reset()                                      # Pulse soft reset, () → None
