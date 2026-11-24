from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1132 import Si1132Full
import time

connection = I2CConnection(0x60)                                         # Create I²C connection, (addr=0x60) → Connection
sensor = Si1132Full(connection)                                          # Create Si1132 driver, (connection) → None
                                                                         # checks PART_ID, writes HW_KEY, UV coefficients and CHLIST

m = sensor.read()                                                        # Read UV index and ALS counts, () → dict {uv_index, visible, infrared}
                                                                         # forced measurement of the enabled channels
print(m)
print('uv index %.2f' % sensor.read_uv_index())                          # Read UV index, () → float
                                                                         # forced measurement, AUX_DATA / 100
print('visible', sensor.read_visible())                                  # Read visible counts, () → int
                                                                         # forced measurement, 16-bit raw count
print('infrared', sensor.read_infrared())                                # Read infrared counts, () → int
                                                                         # forced measurement, 16-bit raw count
print('raw', sensor.read_raw())                                          # Read data registers, () → dict {visible, infrared, aux}
                                                                         # latest values, no measurement is started
print('temperature raw', sensor.read_temperature_raw())                  # Read die temperature, () → int
                                                                         # auxiliary channel, relative only
print('vdd raw', sensor.read_vdd_raw())                                  # Read supply voltage, () → int
                                                                         # auxiliary channel with AUX_ADCMUX = 0x75

sensor.set_channels(True, True, True)                                    # Select channels, (uv, visible, infrared, aux=False) → None
                                                                         # writes CHLIST; uv and aux cannot be combined
sensor.set_gain(visible=2, infrared=2)                                   # Set ADC gain, (visible 0–7, infrared 0–7) → None
                                                                         # integration time x 2^n plus the recommended recovery count
sensor.set_high_range(False, False)                                      # Set high signal range, (visible, infrared) → None
                                                                         # True divides the sensitivity by 14.5 (direct sunlight)
sensor.set_alignment(True, True)                                         # Set data alignment, (visible_low, infrared_low) → None
                                                                         # True reports the least significant 16 bits
sensor.set_uv_coefficients((0x7B, 0x6B, 0x01, 0x00))                     # Set UV coefficients, (ucoef 4 bytes) → None
                                                                         # datasheet defaults for an open-air UV index
print('calibration', sensor.read_calibration())                          # Read factory calibration, () → list of 12 int
                                                                         # GET_CAL then registers 0x22–0x2D

sensor.force()                                                           # Start a forced measurement, () → None
                                                                         # ALS_FORCE without waiting
while not sensor.measurement_ready():                                    # Check measurement done, () → bool
                                                                         # IRQ_STATUS.ALS_INT
    time.sleep(0.001)
print('response', sensor.read_response())                                # Read command response, () → (counter, error)
                                                                         # RESPONSE decoded, error 0 = none
print('poll', sensor.poll_interrupt())                                   # Poll interrupt status, () → int
                                                                         # reads IRQ_STATUS and clears it

sensor.start_autonomous(0.5)                                             # Start autonomous mode, (interval 0–2.05 s) → None
                                                                         # MEAS_RATE in 31.25 µs steps then ALS_AUTO
sensor.enable_interrupt()                                                # Enable the INT pin, () → None
                                                                         # INT_OE and ALS_IE
sensor.on_interrupt(lambda status: print('INT status 0x%02X' % status))  # Subscribe to measurements, (callback, int_pin=None) → None
                                                                         # falling edge on INT, else polling
time.sleep(2)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
sensor.disable_interrupt()                                               # Disable the INT pin, () → None
sensor.pause()                                                           # Pause autonomous mode, () → None
                                                                         # ALS_PAUSE
sensor.set_i2c_address(0x60)                                             # Set I²C address, (address 7-bit) → None
                                                                         # I2C_ADDR parameter then BUSADDR, volatile
sensor.reset()                                                           # Reset the chip, () → None
                                                                         # RESET, waits, writes HW_KEY again
