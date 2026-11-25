"""Complete example for the DRV2605 — exercise every method in the public API.

Constructs the Full driver, plays effects and sequences, streams real-time
amplitudes, configures drive parameters, calibrates, diagnoses and reads the
status registers. program_otp is shown commented out: it is irreversible.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.motor.drv2605 import DRV2605Full, I2C_ADDRESS, EFFECT_STRONG_CLICK, EFFECT_BUZZ

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
haptic = DRV2605Full(connection, lra=False)              # Create DRV2605 Full driver, (connection, lra=False)

print('device id', haptic.read_device_id())             # Read DEVICE_ID, () → int
                                                         # bits 7:5 of STATUS; 3 for the DRV2605
haptic.play_effect(EFFECT_STRONG_CLICK)                  # Play a ROM effect, (effect_id 1–123) → None
                                                         # writes slot 1 and the end marker, then sets GO
haptic.wait_done()                                       # Wait for playback, (timeout_ms=5000) → bool
                                                         # polls GO until the sequence finishes
haptic.play_sequence([EFFECT_STRONG_CLICK, ("wait", 100), EFFECT_BUZZ])  # Play a sequence, (items ≤ 8) → None
                                                         # int = effect, ("wait", ms) = delay in 10 ms steps
haptic.wait_done()                                       # Wait for playback, (timeout_ms=5000) → bool
print('playing', haptic.is_playing())                    # Check playback, () → bool
                                                         # reads the GO bit
haptic.stop()                                            # Cancel playback, () → None
                                                         # clears GO; also leaves RTP mode

haptic.set_library(1)                                    # Select ROM library, (library 0–6) → None
                                                         # 0 empty, 1–5 ERM libraries A–E, 6 LRA library
haptic.set_actuator(False)                               # Select actuator type, (lra bool) → None
                                                         # sets N_ERM_LRA; recalibrate after changing it
haptic.set_open_loop(True)                               # Select open-loop drive, (enabled bool) → None
                                                         # ERM_OPEN_LOOP for an ERM, LRA_OPEN_LOOP for an LRA
haptic.set_bidirectional(True)                           # Select bidirectional input, (enabled bool) → None
                                                         # unidirectional is only valid closed-loop
haptic.set_rated_voltage(volts=3.0)                      # Set rated voltage, (volts V | code 0–255, lra_freq_hz Hz) → None
                                                         # code = V / 21.33 mV for an ERM
haptic.set_overdrive_clamp(volts=3.0)                    # Set overdrive clamp, (volts V | code 0–255, lra_freq_hz Hz) → None
                                                         # code = V / 21.96 mV in open loop
haptic.set_drive_time(2.0)                               # Set drive time, (ms) → None
                                                         # ERM back-EMF sample period (1 ms + 0.2 ms × code)
haptic.set_feedback(brake_factor=3, loop_gain=1, bemf_gain=2)  # Set feedback control, (brake_factor 0–7, loop_gain 0–3, bemf_gain 0–3) → None
                                                         # keeps the actuator-type bit
haptic.set_advanced()                                    # Set CONTROL1–3 fields, (startup_boost, brake_stabilizer, sample_time, blanking_time, idiss_time, lra_drive_twice, supply_comp, noise_gate) → None
                                                         # defaults match the chip's reset values
haptic.set_time_offsets(overdrive_ms=0, sustain_pos_ms=0, sustain_neg_ms=0, brake_ms=0)  # Set library time offsets, (ms ×4, 5 ms steps) → None
                                                         # ODT/SPT/SNT/BRT for open-loop ROM playback

haptic.rtp_level(0.5)                                    # Stream an RTP amplitude, (level 0.0–1.0) → None
                                                         # enters RTP mode with signed data; 0.5 → 64
time.sleep(0.5)
haptic.set_rtp_format(False)                             # Select RTP data format, (unsigned bool) → None
                                                         # False = signed (default)
haptic.rtp_write(0x3F)                                   # Write a raw RTP byte, (value 0–255) → None
                                                         # 0x3F = half scale in signed bidirectional mode
time.sleep(0.5)
haptic.stop()                                            # Cancel playback, () → None
                                                         # zeroes RTP_INPUT and returns to internal trigger

haptic.set_mode(0)                                       # Select operating mode, (mode 0–7) → None
                                                         # 0 internal trigger … 7 auto-calibration
haptic.set_input(False)                                  # Select PWM/analog input, (analog bool) → None
                                                         # N_PWM_ANALOG; used with mode 3
haptic.set_hi_z(False)                                   # Set high-impedance outputs, (enabled bool) → None
                                                         # True floats OUT+/OUT−
ok, comp, bemf, gain = haptic.calibrate(False, 3.0, 3.0)  # Run auto-calibration, (lra, rated_voltage V, overdrive_voltage V, lra_freq_hz Hz) → (bool, int, int, int)
                                                         # takes up to 1.2 s
print('calibrated', ok, comp, bemf, gain)
comp, bemf, gain = haptic.get_calibration()              # Read calibration results, () → (int, int, int)
                                                         # A_CAL_COMP, A_CAL_BEMF, BEMF_GAIN
haptic.set_calibration(comp, bemf, gain)                 # Restore calibration, (comp, bemf, bemf_gain) → None
                                                         # avoids recalibrating after a reset
print('actuator ok', haptic.diagnose())                  # Run actuator diagnostics, () → bool
                                                         # True if DIAG_RESULT = 0
haptic.set_audio_to_vibe(True, 20, 125, 0.1, 1.8, 0.1, 1.0)  # Configure audio-to-vibe, (ac_couple, peak_time_ms, filter_hz, min_input_v, max_input_v, min_drive, max_drive) → None
                                                         # enters audio-to-vibe mode
haptic.set_mode(0)                                       # Select operating mode, (mode 0–7) → None
                                                         # back to internal trigger

haptic.play_effect(EFFECT_BUZZ)                          # Play a ROM effect, (effect_id 1–123) → None
                                                         # a buzz, so the supply and LRA readings are valid
time.sleep(0.2)
print('vdd', haptic.read_supply_voltage())               # Read supply voltage, () → float V
                                                         # VBAT × 5.6 / 255; valid during playback
print('lra', haptic.read_lra_frequency())                # Read LRA frequency, () → float Hz | None
                                                         # None when LRA_PERIOD is 0
haptic.stop()                                            # Cancel playback, () → None
print(haptic.read_status())                              # Read status flags, () → (bool, bool, bool, bool)
                                                         # diag_failed, fb_timeout, over_temp, over_current; clears latches
print('otp', haptic.otp_programmed())                    # Read OTP status, () → bool
                                                         # True once the OTP has been burned
# haptic.program_otp(confirm=True)                       # IRREVERSIBLE one-time OTP burn, (confirm bool) → None
haptic.standby()                                         # Enter software standby, () → None
                                                         # also stops playback
haptic.wake()                                            # Leave software standby, () → None
haptic.reset()                                           # Reset all registers, () → None
                                                         # re-create the driver afterwards
