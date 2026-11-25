import time

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.cs43l22 import CS43L22Full

connection = I2CConnection(bus=1, addr=0x4A)                                # Create I2C connection, (bus=1, addr=0x4A)
dac = CS43L22Full(connection)                                               # Create CS43L22 driver and run the init sequence, (connection, res_pin=None)
                                                                            # reset, ID check, I2S slave defaults, required initialization block

dac.start()                                                                 # Power up the DAC once MCLK is running, () → None
                                                                            # writes POWER_CTL_1 = 0x9E
dac.set_volume(-30.0)                                                       # Set master volume, (db=-102–12 dB) → None
                                                                            # code = round(db * 2) & 0xFF to registers 0x20 / 0x21
print(dac.get_volume())                                                     # Read back master volume, () → float dB
                                                                            # signed register 0x20 / 2
print(dac.read_id())                                                        # Read chip ID and revision, () → (int, int)
                                                                            # register 0x01 split into bits 7:3 and 2:0
dac.set_volume_channels(-20.0, -24.0)                                       # Set per-channel master volume, (left_db, right_db) → None
                                                                            # registers 0x20 and 0x21 independently
dac.set_headphone_volume(-10.0)                                             # Set headphone volume, (left_db, right_db=None) → None
                                                                            # registers 0x22 / 0x23, below -96 dB mutes
dac.set_speaker_volume(-10.0)                                               # Set speaker volume, (left_db, right_db=None) → None
                                                                            # registers 0x24 / 0x25
dac.set_pcm_volume(0.0)                                                     # Set PCM volume, (left_db, right_db=None) → None
                                                                            # 7-bit signed 0.5 dB, -51.5 to +12 dB
dac.mute_pcm(False)                                                         # Unmute PCM channels, (left, right=None) → None
                                                                            # bit 7 of registers 0x1A / 0x1B
dac.set_headphone_gain(CS43L22Full.GAIN_0_6047)                             # Select headphone analog gain, (index=0–7) → None
                                                                            # HPGAIN bits 7:5 of register 0x0D
print(dac.headphone_gain())                                                 # Read headphone gain multiplier, () → float
                                                                            # decodes HPGAIN
dac.mute_outputs(False, True)                                               # Mute outputs, (headphone=None, speaker=None) → None
                                                                            # PLAYBACK_CTL_2 mute bits
dac.set_outputs(CS43L22Full.OUT_ON, CS43L22Full.OUT_OFF)                    # Select amplifier power, (headphone, speaker) → None
                                                                            # POWER_CTL_2 two bits per channel
dac.set_speaker_mode(False, False)                                          # Configure the speaker channels, (mono, swap, mute_50_50=False) → None
                                                                            # SPKMONO / SPKSWAP / MUTE50/50
dac.set_clocking(*dac.clocking_for(12288000, 48000))                        # Configure clocking from the datasheet table, (auto, speed, group_32k, video_clk, ratio, mclk_div2) → None
                                                                            # CLOCKING_CTL register 0x05
dac.set_interface(False, CS43L22Full.FMT_I2S, 0)                            # Configure the serial port, (master=False, fmt=FMT_I2S, word_length=0, dsp=False, invert_sclk=False, sclk_eq_mclk=False, invert_switch=False) → None
                                                                            # registers 0x06 / 0x07
dac.set_tone(3.0, 3.0)                                                      # Configure treble / bass shelves, (treble_db, bass_db, treble_corner=0, bass_corner=0, enabled=True) → None
                                                                            # 1.5 dB per step, TCEN enables
dac.set_beep(0xC, 2, 3, -6.0, CS43L22Full.BEEP_SINGLE)                      # Play a beep, (frequency, on_time, off_time, volume_db, mode, mix=True) → None
                                                                            # timing first, mode last
dac.stop_beep()                                                             # Stop the beep generator, () → None
                                                                            # BEEP[1:0] = 0
dac.set_limiter(True, -6, -12)                                              # Configure the peak limiter, (enabled, max_db=0, cushion_db=0, release=0x3F, attack=0, limit_all=True, soft_ramp_disable=False, zero_cross_disable=False) → None
                                                                            # registers 0x27 - 0x29
dac.set_soft_ramp(True)                                                     # Set soft ramp, (digital, analog_left=False, analog_right=False) → None
                                                                            # DIGSFT and ANLGSFTx
dac.set_zero_cross(False)                                                   # Set zero-cross, (digital, analog_left=False, analog_right=False) → None
                                                                            # DIGZC and ANLGZCx
dac.set_deemphasis(False)                                                   # Set de-emphasis, (enabled) → None
                                                                            # DEEMPH bit of MISC_CTL
dac.set_pcm_swap(CS43L22Full.SRC_NORMAL, CS43L22Full.SRC_NORMAL)            # Select PCM channel sources, (left_source, right_source) → None
                                                                            # PCM_MIXER_SWAP
dac.invert_pcm(False, False)                                                # Invert PCM polarity, (left, right) → None
                                                                            # INV_PCMx
dac.set_passthrough(False)                                                  # Route analog inputs to the outputs, (enabled, left_input=1, right_input=1) → None
                                                                            # PASSTHRUx and PASSxSEL
dac.set_passthrough_volume(0.0)                                             # Set passthrough volume, (left_db, right_db=None) → None
                                                                            # -60 to +12 dB
dac.mute_passthrough(True)                                                  # Mute passthrough, (left, right=None) → None
                                                                            # PASSxMUTE
dac.freeze(False)                                                           # Release the register freeze, (on) → None
                                                                            # FREEZE bit of MISC_CTL
dac.set_battery_compensation(False)                                         # Configure battery compensation, (enabled, vp_ref_index=0, monitor=False) → None
                                                                            # BATTERY_COMP register
print(dac.read_vp_voltage(1.8))                                             # Read the VP supply voltage, (va_volts) → float V
                                                                            # VP_LEVEL * VA / 63.3
print(dac.read_status())                                                    # Read status flags (clears them), () → dict
                                                                            # STATUS register is read-clear
print(dac.read_speaker_status())                                            # Read speaker status, () → dict
                                                                            # short flags and SPKR/HP pin
dac.set_charge_pump_frequency(5)                                            # Set charge-pump frequency divider, (n=0–15) → None
                                                                            # f = 64 * Fs / (n + 2)
print(dac.read_register(0x02))                                              # Read a raw register, (reg) → int
                                                                            # documented registers only
dac.write_register(0x34, 0x5F)                                              # Write a raw register, (reg, value) → None
                                                                            # reserved and read-only registers are rejected
dac.stop()                                                                  # Power down the DAC, () → None
                                                                            # mute, disable ramps, POWER_CTL_1 = 0x9F, wait 100 us
