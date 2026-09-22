from machine import Pin, SPI

from periph.chips.adc_dac.ad7706 import AD7706Full
from periph.connection.spi_micropython import SPIConnection

FILTER_DP_THRESHOLD = 0.001

spi = SPI(1, baudrate=1_000_000, polarity=1, phase=1)
cs = Pin(15, Pin.OUT, value=1)

conn = SPIConnection(spi, cs)
adc = AD7706Full(conn, vref=2.5, mclk_hz=2_457_600)                                      # Create AD7706 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → None

# --- Configure all three channels for the HVAC manifold-pressure application ---
# All three pressure transducers are bridge-type with small mV-level output
# signals, so the AD7706's high-gain (128) pseudo-differential input is ideal.
# The shared-COMMON architecture lets all three bridges share a single return
# line instead of three fully-differential pairs.
adc.configure(channel=1, gain=128, bipolar=True, buffered=True, output_rate_hz=50)       # Configure channel 1, (channel=1, gain=1, bipolar=True, buffered=False, output_rate_hz=50) → None
adc.configure(channel=2, gain=128, bipolar=True, buffered=True, output_rate_hz=50)       # Configure channel 2, (channel=1, gain=1, bipolar=True, buffered=False, output_rate_hz=50) → None
adc.configure(channel=3, gain=128, bipolar=True, buffered=True, output_rate_hz=50)       # Configure channel 3, (channel=1, gain=1, bipolar=True, buffered=False, output_rate_hz=50) → None

# --- Self-calibrate all three channels before the measurement loop ---
adc.self_calibrate(channel=1)                                                            # Self-calibrate channel, (channel=1) → None
adc.self_calibrate(channel=2)                                                            # Self-calibrate channel, (channel=1) → None
adc.self_calibrate(channel=3)                                                            # Self-calibrate channel, (channel=1) → None

# --- Sample continuously and compute filter differential pressure ---
# Channel 1 = filter-inlet static, Channel 2 = filter-outlet static, Channel 3 = duct static.
# filter_dp = ch1 - ch2 is the filter differential pressure (clog indicator).
last_filter_dp = None
while True:
    inlet = adc.read_voltage(channel=1)                                                  # Read voltage, (channel=1) → float V
    outlet = adc.read_voltage(channel=2)                                                 # Read voltage, (channel=2) → float V
    duct = adc.read_voltage(channel=3)                                                   # Read voltage, (channel=3) → float V
    filter_dp = inlet - outlet
    if last_filter_dp is None or abs(filter_dp - last_filter_dp) > FILTER_DP_THRESHOLD:
        print("→ filter_dp={:.4f} V, duct_pressure={:.4f} V".format(filter_dp, duct))
        last_filter_dp = filter_dp
