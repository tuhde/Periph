from machine import Pin, SPI

from periph.chips.adc_dac.ad7705 import AD7705Full
from periph.connection.spi_micropython import SPIConnection

spi = SPI(1, baudrate=1_000_000, polarity=1, phase=1)
cs = Pin(15, Pin.OUT, value=1)
reset = Pin(16, Pin.OUT, value=1)

conn = SPIConnection(spi, cs)
adc = AD7705Full(conn, vref=2.5, mclk_hz=2_457_600, reset_pin=reset)                    # Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=None) → None
                                                                                         # constructor runs the Initialization Sequence (gain 1, bipolar, 50 Hz, self-calibrate Channel 1)

adc.configure(channel=2, gain=8, bipolar=True, buffered=True, output_rate_hz=60)        # Configure channel 2, (channel=1, gain=1, bipolar=True, buffered=False, output_rate_hz=50) → None
                                                                                         # sets gain/bipolar/buffered/output_rate for the given channel; does not calibrate
adc.self_calibrate(channel=2)                                                           # Self-calibrate channel, (channel=1) → None
                                                                                         # runs internal self-calibration, blocking until DRDY indicates completion

off2 = adc.get_offset_calibration(channel=2)                                            # Read offset calibration, (channel=1) → int 24-bit
gain2 = adc.get_gain_calibration(channel=2)                                             # Read gain calibration, (channel=1) → int 24-bit
print("ch2 offset=", off2, "gain=", gain2)

raw1 = adc.read_raw(channel=1)                                                          # Read raw 16-bit code, (channel=1) → int 16-bit
                                                                                         # blocks until DRDY, returns raw Data Register code
v1 = adc.read_voltage(channel=1)                                                        # Read voltage, (channel=1) → float V
                                                                                         # converts raw code to volts using channel's current gain/bipolar setting
v2 = adc.read_voltage(channel=2)                                                        # Read voltage, (channel=2) → float V
print("ch1 raw=", raw1, "ch1 v=", v1, "ch2 v=", v2)

adc.standby()                                                                           # Enter standby, () → None
                                                                                         # sets STBY=1 (~10 µA, registers retained)
adc.wakeup()                                                                             # Exit standby, () → None
                                                                                         # clears STBY; blocks until a fresh conversion is available

adc.reset()                                                                              # Hardware reset, () → None
                                                                                         # pulses RESET low for >=100 ns; all registers return to power-on defaults
