from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1145 import Si1145Full
import time

connection = I2CConnection(0x60)                                         # Create I²C connection, (addr=0x60) → Connection
# The Si1145 has the fixed address 0x60 (older silicon: 0x5A).
sensor = Si1145Full(connection)                                          # Create Si1145 driver, (connection) → None
# Constructor stores the connection only; no bus traffic yet.
sensor.init()                                                            # Check PART_ID, HW_KEY, UV + ALS + PS1, () → None
# Waits 25 ms, verifies PART_ID 0x45/0x46/0x47, writes HW_KEY 0x17, UCOEF, CHLIST 0xB1, LED1 level 3.

print(sensor.read_id())                                                  # Identity registers, () → (part_id, rev_id, seq_id)
# PART_ID 0x45 for the Si1145; SEQ_ID 0x01 parts swap the MEAS_RATE bytes.
print(sensor.read_response())                                            # RESPONSE decoded, () → (counter, error)
# 4-bit command counter and the error code (0 = none, 0x8x = ADC overflow / invalid setting).

print(sensor.read())                                                     # Forced PS + ALS, () → dict {proximity, uv_index, visible, infrared}
# PSALS_FORCE, waits for ALS_INT and PS1_INT, raw counts and the UV index (AUX_DATA / 100).
print(sensor.read_proximity(1))                                          # Forced PS, (channel=1) → int counts
# PS1 only; PS2/PS3 exist on the Si1146/47.
print(sensor.read_uv_index())                                            # Forced ALS, () → float UV index
# UV index with the default UV coefficients 7B 6B 01 00.
print(sensor.read_visible())                                             # Forced ALS, () → int counts
# Raw visible ambient light.
print(sensor.read_infrared())                                            # Forced ALS, () → int counts
# Raw infrared ambient light.
print(sensor.read_raw())                                                 # Data registers, () → dict {visible, infrared, ps1, ps2, ps3, aux}
# No new measurement; the latest values of 0x22–0x2D.
print(sensor.read_temperature_raw())                                     # AUX temperature, () → int counts
# Relative die temperature; CHLIST is switched to EN_AUX and restored.
print(sensor.read_vdd_raw())                                             # AUX VDD, () → int counts
# Supply voltage on the auxiliary channel.

sensor.set_channels(True, True, True, True)                              # CHLIST, (uv, visible, infrared, ps1, ps2=False, ps3=False, aux=False) → None
# UV and AUX share AUX_DATA, so only one of them can be enabled.
sensor.set_led_current(1, 8)                                             # LED drive, (led, level) → None
# Level 0 = off … 15 = maximum (~359 mA); keep the gain low at high currents.
sensor.set_ps_led(1, 0x01)                                               # PS LED select, (channel, leds) → None
# PS1 drives LED1; the Si1145 has no LED2/LED3.
sensor.set_ps_photodiode(1, True)                                        # PS photodiode, (channel, large) → None
# Large IR photodiode (0x03); False selects the small one.
sensor.set_gain(visible=0, infrared=0, proximity=0)                      # ADC gains, (visible, infrared, proximity) → None
# Integration time × 2^n, recovery counts at the one's complement of the gain.
sensor.set_high_range(False, False, False)                               # Signal range, (visible, infrared, proximity) → None
# True divides the sensitivity by 14.5 (direct sunlight).
sensor.set_alignment()                                                   # Result alignment, (visible, infrared, ps1, ps2, ps3) → None
# True reports the 16 LSBs instead of the 16 MSBs of the ADC result.
sensor.set_uv_coefficients(Si1145Full.UCOEF_DEFAULT)                     # UCOEF0..3, (ucoef) → None
# 7B 6B 01 00 make AUX_DATA the UV index × 100.
print(sensor.read_calibration())                                         # GET_CAL, () → list[int] 12 bytes
# Factory calibration bytes; overwrites the data registers.

sensor.poll_interrupt()                                                  # IRQ_STATUS, () → int sources
# Reads and clears pending flags.
sensor.start_autonomous(0.1)                                             # Autonomous mode, (interval s, proximity=True, als=True) → None
# MEAS_RATE = 0.1 s / 31.25 µs = 3200, then PSALS_AUTO.
time.sleep(0.5)
print(sensor.poll_interrupt())                                           # IRQ_STATUS, () → int sources
# SOURCE_ALS | SOURCE_PS1 when both finished at least once.
print(sensor.read_raw())                                                 # Data registers, () → dict
# Latest autonomous results.
sensor.pause()                                                           # PSALS_PAUSE, () → None
# Stops the autonomous loop.
sensor.force(proximity=True, als=False)                                  # PS_FORCE without waiting, (proximity, als) → None
# The result arrives when IRQ_STATUS flags PS1.
time.sleep(0.05)
print(sensor.read_raw()['ps1'])

events = []
sensor.enable_interrupt(Si1145Full.SOURCE_ALS | Si1145Full.SOURCE_PS1)   # INT_CFG + IRQ_ENABLE, (source) → None
# INT pin (active low) driven for ALS and PS1 completions.
sensor.on_interrupt(events.append)                                       # Subscribe, (callback, int_pin=None) → None
# Falling edge on the connection's int_pin, otherwise a 10 ms polling thread.
sensor.force()                                                           # PSALS_FORCE without waiting, () → None
time.sleep(0.2)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
print(events)
sensor.disable_interrupt(Si1145Full.SOURCE_CMD)                          # IRQ_ENABLE clear, (source) → None
# CMD_INT is not used by default.

sensor.set_i2c_address(0x60)                                             # I2C_ADDR + BUSADDR, (address) → None
# Volatile; here the address stays 0x60, otherwise reconnect on the new address.
sensor.reset()                                                           # RESET + HW_KEY + defaults, () → None
# Software reset, 10 ms wait, then the Minimal configuration again.
