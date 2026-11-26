from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1133 import Si1133Full
import time

connection = I2CConnection(0x55)                                         # Create I²C connection, (addr=0x55) → Connection
sensor = Si1133Full(connection)                                          # Create Si1133 driver, (connection) → None
sensor.init()                                                            # Check PART_ID, reset, UV + white channels, () → None

# --- Identity ---
# PART_ID 0x33 identifies the Si1133; HW_ID and REV_ID give silicon and
# product revision. RESPONSE0 shows the sequencer state and command counter.
part_id, hw_id, rev_id = sensor.read_id()                                # Identity registers, () → (part_id, hw_id, rev_id)
print('PART_ID 0x%02X  HW_ID 0x%02X  REV_ID 0x%02X' % (part_id, hw_id, rev_id))
print('response', sensor.read_response())                                # RESPONSE0 decoded, () → (state, error, counter)

# --- UV and daylight monitor: 20 s at 1 Hz ---
# The counts are raw ADC values; a UV index needs Silicon Labs' calibration
# for the product's window, so watch the trend.
for i in range(20):
    m = sensor.read()                                                    # Forced measurement, () → dict {uv counts, white counts}
    print('%2d s  UV %8d  white %8d' % (i, m['uv'], m['white']))
    time.sleep(1)

# --- Gain settings on a constant light level ---
# HW_GAIN doubles the integration time per step, SW_GAIN accumulates 2^n
# samples, HIGH_SIGNAL divides the sensitivity by 14.5 for direct sunlight.
sensor.enable_channels(0x04)                                             # CHAN_LIST, (mask) → None
for hw, sw, hsig in ((2, 0, False), (6, 0, False), (6, 2, False), (6, 0, True)):
    sensor.configure_channel(2, Si1133Full.MUX_WHITE, hw_gain=hw, sw_gain=sw,
                             high_signal=hsig)                           # Channel setup, (channel, adcmux, hw_gain, sw_gain, ..., high_signal) → None
    print('hw_gain %d sw_gain %d high_signal %d -> %d'
          % (hw, sw, hsig, sensor.read_channels()[0]))                   # Forced read, () → list[int]

# --- IR photodiodes ---
for name, mux in (('small', Si1133Full.MUX_SMALL_IR), ('medium', Si1133Full.MUX_MEDIUM_IR),
                  ('large', Si1133Full.MUX_LARGE_IR)):
    print('IR %-6s %d' % (name, sensor.read_ir(mux)))                    # IR photodiode once, (photodiode) → int counts
print('UV %d, white %d' % (sensor.read_uv(), sensor.read_white()))       # Scratch-channel reads, () → int counts

# --- Autonomous mode with a UV threshold ---
# Channels 0 and 1 run every second; channel 0 only interrupts when crossing
# THRESHOLD0, channel 1 on every result.
sensor.configure_channel(0, Si1133Full.MUX_UV, hw_gain=9)                # Channel setup, (channel, adcmux, hw_gain) → None
sensor.enable_channels(0x03)                                             # CHAN_LIST, (mask) → None
sensor.set_threshold(0, 500)                                             # Threshold value, (index, value) → None
sensor.set_channel_threshold(0, 1)                                       # Threshold select, (channel, index) → None
sensor.set_channel_counter(1, 1)                                         # Counter for a channel, (channel, index) → None
sensor.disable_interrupt(Si1133Full.SOURCE_CHANNEL1)                    # IRQ_ENABLE clear, (source) → None
sensor.enable_interrupt(Si1133Full.SOURCE_CHANNEL0)                     # IRQ_ENABLE set, (source) → None


def on_event(status):
    print('interrupt 0x%02X  values %s' % (status, sensor.read_channels(False)))


sensor.on_interrupt(on_event)                                            # Subscribe, (callback, int_pin=None) → None
sensor.start_autonomous(0.1, (10, 10, 10))                               # Autonomous mode, (rate s, counters) → None
time.sleep(5)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
sensor.pause()                                                           # PAUSE, () → None
sensor.enable_interrupt(Si1133Full.SOURCE_CHANNEL1)                     # IRQ_ENABLE set, (source) → None

# --- Burst mode: four readings in a row ---
sensor.set_burst(4)                                                      # Burst mode, (count) → None
sensor.force()                                                           # FORCE without waiting, () → None
time.sleep(0.5)
print('burst ready 0x%02X' % sensor.data_ready())                        # IRQ_STATUS mask, () → int
print('burst values', sensor.read_channels(False))                       # Decode current results, (force=False) → list[int]
sensor.set_burst(0)

# --- Parameter table ---
sensor.write_parameter(0x1E, 7)                                          # PARAM_SET, (address, value) → None
print('CHAN_LIST 0x%02X  MEASRATE %d  MEASCOUNT2 %d' % (
    sensor.read_parameter(0x01),                                         # PARAM_QUERY, (address) → int
    (sensor.read_parameter(0x1A) << 8) | sensor.read_parameter(0x1B),
    sensor.read_parameter(0x1E)))
print('poll', sensor.poll_interrupt())                                   # IRQ_STATUS mask, () → int

# --- Finish ---
# set_i2c_address would move the chip to another address until the next
# reset; the demo keeps 0x55 and only shows the call.
# sensor.set_i2c_address(0x56)                                           # Change address, (address) → None
sensor.reset()                                                           # RESET_SW + defaults, () → None
print('after reset', sensor.read())                                      # Forced measurement, () → dict {uv, white}
