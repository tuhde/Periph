import time

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.es8311 import ES8311Full

# --- Bring up the codec for a voice assistant ---
# The control port only configures the codec; the I2S stream (MCLK, BCLK,
# LRCK, DSDIN, ASDOUT) is produced elsewhere. 16 kHz is the usual rate for
# speech recognition.
connection = I2CConnection(bus=1, addr=0x18)                 # Create I2C connection, (bus=1, addr=0x18)
codec = ES8311Full(connection, 12288000, 48000)              # Create ES8311 driver, (connection, mclk_hz=12288000 Hz, sample_rate_hz=48000 Hz)
codec.configure_clock(12288000, 16000)                       # Program the clock tree, (mclk_hz=12288000 Hz, sample_rate_hz=16000 Hz) → None
codec.configure_serial('i2s', 16)                            # Configure the digital audio port, (format='i2s', word_length=16) → None

# --- Configure the microphone path ---
# 24 dB analog gain plus the ALC keeps quiet and loud talkers at a similar
# level; the noise gate and automute silence the line between utterances.
codec.set_mic_gain(24)                                       # Set microphone PGA gain, (db=0–30 dB) → None
print(codec.get_mic_gain())                                  # Read back PGA gain, () → float dB
codec.set_adc_volume(0)                                      # Set ADC digital volume, (db=-95.5–32 dB) → None
codec.configure_alc(True, max_level_db=-12.0, min_level_db=-18.1, automute=True, noise_gate_db=-60)  # Configure ALC, (enabled, max_level_db, min_level_db, window, automute, automute_window, noise_gate_db) → None
codec.configure_adc(hpf=True)                                # Configure ADC path, (scale_db=24 dB, osr=64, double_speed=False, hpf=True, hpf_coeffs=(0x0C, 0x0C)) → None

# --- Configure playback ---
# The DRC compresses loud peaks so a small speaker does not clip; the soft
# ramp avoids clicks while the volume changes.
codec.configure_drc(True, max_level_db=-12.0, min_level_db=-24.1)  # Configure DRC, (enabled, max_level_db, min_level_db, window) → None
codec.set_dac_ramp(8)                                        # Set DAC soft-ramp rate, (rate=0–15) → None
codec.set_mute(False)                                        # Unmute the DAC, (muted=False) → None

# --- Ramp the playback volume ---
# Step from -60 dB to -10 dB, reading the value back each time.
for vol in range(-60, -9, 10):
    codec.set_volume(vol)                                    # Set DAC volume, (db=-95.5–32 dB) → None
    print('volume', codec.get_volume(), 'dB')                # Read back DAC volume, () → float dB
    time.sleep(0.5)

# --- Echo-cancellation feedback and loopback ---
# "dac_adc" puts the DAC data next to the microphone on ADCDAT so the host
# can cancel the speaker echo; the loopback routes the microphone to the
# speaker for a quick acoustic check.
codec.set_adcdat_mode('dac_adc')                             # Select ADCDAT content, (mode) → None
time.sleep(2)
codec.set_adcdat_mode('adc_adc')                             # Restore ADCDAT content, (mode) → None
codec.set_loopback(True)                                     # Route ADC output into the DAC, (enabled) → None
time.sleep(2)
codec.set_loopback(False)                                    # Disable loopback, (enabled) → None

# --- Shut down ---
# Mute first so power-down does not pop, then enter standby.
codec.set_mute(True)                                         # Mute the DAC, (muted=True) → None
codec.power_down()                                           # Enter standby, () → None
