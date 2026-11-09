import time

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.es8311 import ES8311Minimal

connection = I2CConnection(bus=1, addr=0x18)                 # Create I2C connection, (bus=1, addr=0x18)
codec = ES8311Minimal(connection, 12288000, 48000)           # Create ES8311 driver and run the init sequence, (connection, mclk_hz=12288000 Hz, sample_rate_hz=48000 Hz)

codec.set_mic_gain(24)                                       # Set microphone PGA gain, (db=0–30 dB, 3 dB steps) → None
codec.set_mute(False)                                        # Unmute the DAC, (muted=False) → None
codec.set_volume(-20.0)                                      # Set DAC volume, (db=-95.5–32 dB, 0.5 dB steps) → None

time.sleep(5)
codec.set_mute(True)                                         # Mute the DAC, (muted=True) → None
