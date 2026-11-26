from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1133 import Si1133Full
import time

connection = I2CConnection(0x55)                                         # Create I²C connection, (addr=0x55) → Connection
# Bind the connection to the chip's default address (AD pulled up).
sensor = Si1133Full(connection)                                          # Create Si1133 driver, (connection) → None
# Constructor stores the connection only; no bus traffic yet.
sensor.init()                                                            # Check PART_ID, reset, UV + white channels, () → None
# Waits 25 ms, verifies PART_ID 0x33, RESET_SW, channel 0 = UV, channel 1 = white, 24-bit outputs.

print(sensor.read_id())                                                  # Identity registers, () → (part_id, hw_id, rev_id)
# PART_ID is 0x33; HW_ID bits 4:0 are 0x03 for the Si1133-AA00.
print(sensor.read_response())                                            # RESPONSE0 decoded, () → (state, error, counter)
# State bits RUNNING/SUSPEND/SLEEP, the CMD_ERR flag and the 4-bit command counter.

print(sensor.read())                                                     # Forced measurement, () → dict {uv counts, white counts}
# FORCE, wait for both channels in IRQ_STATUS, decode two signed 24-bit counts.
print(sensor.read_uv())                                                  # UV photodiode once, () → int counts
# Uses channel 5 as a scratch channel and restores CHAN_LIST afterwards.
print(sensor.read_white())                                               # White photodiode once, () → int counts
# Same scratch channel, HW_GAIN 7.
print(sensor.read_ir(Si1133Full.MUX_LARGE_IR))                           # IR photodiode once, (photodiode) → int counts
# Small, medium or large IR photodiode; the small one is the default.

sensor.configure_channel(2, Si1133Full.MUX_LARGE_WHITE, hw_gain=4, sw_gain=1, decim_rate=0,
                         high_signal=False, bits24=False, postshift=0)   # Channel setup, (channel, adcmux, hw_gain, sw_gain, decim_rate, high_signal, bits24, postshift) → None
# Writes ADCCONFIG2, ADCSENS2 and ADCPOST2; this channel reports 16-bit unsigned counts.
sensor.enable_channels(0x07)                                             # CHAN_LIST, (mask) → None
# Channels 0, 1 and 2 now run on every FORCE.
print(sensor.read_channels())                                            # Forced read of all channels, (force=True) → list[int]
# Results in channel order: 24-bit signed for channels 0/1, 16-bit for channel 2.
sensor.force()                                                           # FORCE without waiting, () → None
# Starts a measurement; the results arrive in HOSTOUT when IRQ_STATUS flags the channels.
time.sleep(0.1)
print(sensor.data_ready())                                               # IRQ_STATUS mask, () → int
# Bit c set when channel c finished; this read clears the flags.
print(sensor.read_channels(False))                                       # Decode current results, (force=False) → list[int]
# No new measurement, only HOSTOUT decoded.

sensor.set_threshold(0, 2000)                                            # Threshold value, (index, value) → None
# THRESHOLD0 = 2000 counts (16-bit).
sensor.set_channel_threshold(0, 1)                                       # Threshold select, (channel, index) → None
# Channel 0 interrupts only when crossing THRESHOLD0 (index 1); 0 turns it off.
sensor.set_channel_threshold(0, 0)
sensor.set_channel_counter(2, 2)                                         # Counter for a channel, (channel, index) → None
# Channel 2 uses MEASCOUNT1 in autonomous mode; 0 keeps a channel forced-only.
sensor.start_autonomous(0.1, (10, 5, 1))                                 # Autonomous mode, (rate s, counters) → None
# MEASRATE = 0.1 s; channels 0/1 run every 1 s (MEASCOUNT0 = 10), channel 2 every 0.5 s.
time.sleep(1.5)
print(sensor.poll_interrupt())                                           # IRQ_STATUS mask, () → int
# Same as data_ready; also releases the INT pin.
sensor.pause()                                                           # PAUSE, () → None
# Stops the autonomous measurements.
sensor.set_burst(4)                                                      # Burst mode, (count) → None
# Four readings per burst at MEASRATE; 0 disables burst mode.
sensor.set_burst(0)

sensor.write_parameter(0x1E, 3)                                          # PARAM_SET, (address, value) → None
# Direct parameter-table access: MEASCOUNT2 = 3.
print(sensor.read_parameter(0x1E))                                       # PARAM_QUERY, (address) → int
# Reads it back through RESPONSE1.

sensor.disable_interrupt(Si1133Full.SOURCE_CHANNEL2)                    # IRQ_ENABLE clear, (source) → None
# Channel 2 no longer drives INT.
sensor.enable_interrupt(Si1133Full.SOURCE_CHANNEL2)                     # IRQ_ENABLE set, (source) → None
# Channel 2 drives INT again.
sensor.on_interrupt(lambda status: print('irq 0x%02X' % status))         # Subscribe, (callback, int_pin=None) → None
# INT falling edge when a pin is wired, otherwise a 10 ms IRQ_STATUS poll.
sensor.read_channels()
time.sleep(0.1)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
# Stops the pin handler or polling thread.

sensor.reset()                                                           # RESET_SW + defaults, () → None
# Parameter table back to power-up values, then channel 0 = UV, channel 1 = white again.
# sensor.set_i2c_address(0x56)                                           # Change address, (address) → None
# Volatile; the chip answers on the new address at once, so a new connection is needed.
