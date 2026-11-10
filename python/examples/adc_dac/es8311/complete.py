import time

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.es8311 import ES8311Full

connection = I2CConnection(bus=1, addr=0x18)                 # Create I2C connection, (bus=1, addr=0x18)
codec = ES8311Full(connection, 12288000, 48000)              # Create ES8311 driver and run the init sequence, (connection, mclk_hz=12288000 Hz, sample_rate_hz=48000 Hz)
                                                             # reset, clock dividers, I2S 16-bit slave, analog path, mic 24 dB, DAC silent

codec.set_volume(-30.0)                                      # Set DAC volume, (db=-95.5–32 dB) → None
                                                             # writes 0x32 = round(db / 0.5) + 191
print(codec.get_volume())                                    # Read back DAC volume, () → float dB
                                                             # (0x32 - 191) * 0.5
codec.set_mute(False)                                        # Unmute the DAC, (muted=False) → None
                                                             # clears DSM and DEM mute bits of 0x31
codec.set_mic_gain(27)                                       # Set microphone PGA gain, (db=0–30 dB) → None
                                                             # 0x14 bits 3:0 = round(db / 3)
print(codec.get_mic_gain())                                  # Read back PGA gain, () → float dB
                                                             # 3 dB per register step
codec.configure_clock(12288000, 16000)                       # Program the clock tree, (mclk_hz=12288000 Hz, sample_rate_hz=16000 Hz, mclk_from_bclk=False, mclk_invert=False) → None
                                                             # preset table first, then computed pre-divider / multiplier / OSR
codec.configure_serial('i2s', 16, False, False, False, 'left')  # Configure the digital audio port, (format, word_length=16, master=False, bclk_invert=False, lrck_invert=False, dac_channel='left') → None
                                                             # 0x09 / 0x0A format and word length, slave mode
codec.set_tristate(False, False)                             # Tri-state BCLK/LRCK and ADCDAT, (bclk_lrck=False, adcdat=False) → None
                                                             # 0x07 bits 5:4
codec.set_input('analog')                                    # Select microphone input, (source='analog'|'digital'|'none') → None
                                                             # MIC1P / MIC1N differential, PGA powered
codec.configure_dmic(False)                                  # Select PDM latch edge, (latch_falling_edge=False) → None
                                                             # 0x15 bit 0
codec.set_adc_volume(0)                                      # Set ADC digital volume, (db=-95.5–32 dB) → None
                                                             # 0x17; ALC maximum gain when the ALC is on
codec.configure_adc(24, 64, False, True, (0x0C, 0x0C))       # Configure ADC path, (scale_db=24 dB, osr=64, double_speed=False, hpf=True, hpf_coeffs=(0x0C, 0x0C)) → None
                                                             # 0x16 scale, 0x03 speed / OSR, 0x1B / 0x1C high-pass filter
codec.set_adc_ramp(0)                                        # Set ADC soft-ramp rate, (rate=0–15) → None
                                                             # 0 = off; n = 0.25 dB per 2^(n+1) LRCK
codec.configure_alc(True, -12.0, -18.1, 0, True, 0, -60)     # Configure ALC, (enabled, max_level_db, min_level_db, window, automute, automute_window, noise_gate_db) → None
                                                             # 0x18–0x1A, levels rounded to the nearest listed value
codec.configure_alc(False)                                   # Disable ALC, (enabled=False) → None
                                                             # restores defaults
codec.set_adc_eq(None)                                       # Bypass ADC equalizer, (coefficients=None) → None
                                                             # 0x1C bit 6; a tuple of five 30-bit values loads b0,a1,a2,b1,b2
codec.set_dac_ramp(8)                                        # Set DAC soft-ramp rate, (rate=0–15) → None
                                                             # 0x37 bits 7:4
codec.configure_drc(True, -12.0, -24.1, 0)                   # Configure DRC, (enabled, max_level_db, min_level_db, window) → None
                                                             # 0x34 / 0x35; DAC volume becomes the maximum gain
codec.configure_drc(False)                                   # Disable DRC, (enabled=False) → None
                                                             # clears the DRC enable bit
codec.set_dac_eq(None)                                       # Bypass DAC equalizer, (coefficients=None) → None
                                                             # 0x37 bit 3; a tuple of three 30-bit values loads b0,b1,a1
codec.set_dac_mute_options(True, True, False)                # Set DAC mute bits, (dsm=True, dem=True, dsm_target=False) → None
                                                             # 0x31 bits 7:5 individually
codec.set_loopback(True)                                     # Route ADC output into the DAC, (enabled) → None
                                                             # 0x44 bit 7
codec.set_loopback(False)                                    # Disable loopback, (enabled=False) → None
                                                             # 0x44 bit 7 cleared
codec.set_adcdat_mode('dac_adc')                             # Select ADCDAT content, (mode='adc_adc'|'adc_zero'|'zero_adc'|'dac_adc'|'adc_dac'|'dac_dac') → None
                                                             # DAC + ADC: digital feedback for echo cancellation
codec.set_adcdat_mode('adc_adc')                             # Restore ADCDAT content, (mode='adc_adc') → None
                                                             # ADC data on both slots
codec.set_sdp_mute(False, False)                             # Mute serial input / output, (input=False, output=False) → None
                                                             # 0x09 / 0x0A bit 6
codec.set_low_power(False, False, False, False, False, False, False, False)  # Set low-power bits, (dac, pga, pga_out, adc, adc_ref, dac_ref, flash, integrator) → None
                                                             # 0x0F; all off = best THD+N
print(codec.read_register(0x32))                             # Read raw register, (register=0x00–0xFF) → int
                                                             # returns the byte as stored in the chip
codec.write_register(0x32, 0xBF)                             # Write raw register, (register, value) → None
                                                             # 0xBF = 0 dB DAC volume
time.sleep(1)
codec.set_mute(True)                                         # Mute the DAC, (muted=True) → None
                                                             # sets DSM and DEM mute
codec.power_down()                                           # Enter standby, () → None
                                                             # powers down analog blocks, resets digital, clears CSM_ON
codec.power_up()                                             # Leave standby, () → None
                                                             # re-enables analog blocks, clocks and CSM_ON
codec.reset()                                                # Software reset, () → None
                                                             # registers return to reset values; reconfigure afterwards
