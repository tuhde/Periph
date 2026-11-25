from machine import Pin, SPI
import time

from periph.chips.adc_dac.hy3131 import HY3131Full
from periph.connection.spi_micropython import SPIConnection

spi = SPI(1, baudrate=1_000_000, polarity=0, phase=0)
cs = Pin(15, Pin.OUT, value=1)
conn = SPIConnection(spi, cs)                                                          # Open SPI bus 1 with CS on pin 15, (bus, cs, int_pin=None, en_pin=None) → SPIConnection

# --- Bench voltmeter with on-chip temperature and AC analysis ---
# A 0–1.2 V source sits between PB2 and AGND. AD1 runs at ~40 Hz with the chopper on
# for offset-free DC readings; AD2 + LPF + RMS + peak hold watch the same node for AC.
adc = HY3131Full(conn)                                                                  # Create HY3131 driver, (connection, vref=1.2 V) → None
adc.configure_ad1(osr=4, chop=2, input_gain=0, ref_gain=0, offset=0, buffers=0)         # Configure AD1, (osr=7, chop=0, input_gain=0, ref_gain=0, offset=0, buffers=0) → None

# --- One-point temperature calibration ---
# Run the board at 25 °C (or replace with a stored constant) and remember the code.
tcode_25 = adc.read_temperature_code()                                                  # Read temperature code, () → int

# --- Enable AC measurement chain on the same input ---
adc.enable_ad2(osr=5, input_gain=1, source=2)                                           # Enable AD2, (osr=5, input_gain=1, ref_gain=0, chop=False, clk_div4=False, source=0, use_ad3=False) → None
adc.enable_lpf(bw=3)                                                                    # Enable low-pass filter, (bw=0) → None
adc.enable_rms()                                                                        # Enable RMS converter, () → None
adc.enable_peak_hold(source=0)                                                          # Enable peak hold, (source=0) → None

while True:
    v = adc.read_voltage()                                                              # Read AD1 voltage, () → float V
    t = adc.read_temperature(tcode_25)                                                  # Read die temperature, (tcode_25) → float °C
    rms = adc.read_rms()                                                                # Read RMS, () → float LSB
    lo, hi = adc.read_peak()                                                            # Read peak hold, () → (int, int) 19-bit
    print("V={:.4f} V  T={:.1f} C  rms={:.0f}  p-p={}".format(v, t, rms, hi - lo))
    time.sleep(1)
