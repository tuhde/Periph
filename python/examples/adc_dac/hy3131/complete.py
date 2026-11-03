from machine import Pin, SPI

from periph.chips.adc_dac.hy3131 import HY3131Full
from periph.connection.spi_micropython import SPIConnection

spi = SPI(1, baudrate=1_000_000, polarity=0, phase=0)
cs = Pin(15, Pin.OUT, value=1)

conn = SPIConnection(spi, cs)
adc = HY3131Full(conn)                                                                  # Create HY3131 driver, (connection, vref=1.2 V) → None
                                                                                         # constructor resets the chip and applies the Minimal defaults (AD1 on PB2/AGND, 5 Hz)

adc.configure_ad1(osr=4, chop=2, input_gain=0, ref_gain=0, offset=0, buffers=0)         # Configure AD1, (osr=7, chop=0, input_gain=0, ref_gain=0, offset=0, buffers=0) → None
                                                                                         # OSR 4096 (~40 Hz), chopper on (offset cancelled, rate halved), gain 0.9
adc.set_ad1_input(10, 3, filter_r=2)                                                    # Select AD1 inputs, (pos=SAD1FP 0–15, neg=SAD1FN 0–7, filter_r=2) → None
                                                                                         # PB2 vs AGND with the 100 k/10 k filter resistor bypassed
adc.set_ad1_reference(1, 1)                                                             # Select AD1 reference, (high=0–6, low=0–6) → None
                                                                                         # REFO (+) / AGND (−)

raw = adc.read_raw()                                                                    # Read AD1 code, () → int 24-bit
                                                                                         # waits for AD1F in INTF, returns the sign-extended code
v = adc.read_voltage()                                                                  # Read AD1 voltage, () → float V
                                                                                         # code / 2^23 · RG − DCOS, scaled by vref / input gain
print("raw=", raw, "v=", v)

tcode = adc.read_temperature_code()                                                     # Read temperature code, () → int
                                                                                         # (AD1[TS pair 2] − AD1[TS pair 1]) / 2; note this value at 25 °C
t = adc.read_temperature(tcode)                                                         # Read die temperature, (tcode_25) → float °C
                                                                                         # one-point calibrated against tcode_25
print("temperature=", t)

adc.enable_ad2(osr=5, input_gain=1, ref_gain=0, chop=False, clk_div4=False, source=0, use_ad3=False)  # Enable AD2, (osr=5, input_gain=1, ref_gain=0, chop=False, clk_div4=False, source=0, use_ad3=False) → None
                                                                                         # AD2 samples OP1O at gain 1.0, OSR 1024
adc2 = adc.read_ad2_raw()                                                               # Read AD2 code, () → int 19-bit
                                                                                         # waits for AD2F
adc.enable_lpf(bw=0)                                                                    # Enable low-pass filter, (bw=0) → None
                                                                                         # OSR4 = 128
lpf = adc.read_lpf_raw()                                                                # Read LPF code, () → int 19-bit
                                                                                         # waits for LPFF
adc.enable_rms()                                                                        # Enable RMS converter, () → None
                                                                                         # accumulates the squared LPF input
rms = adc.read_rms()                                                                    # Read RMS, () → float LSB
                                                                                         # square root of the accumulator
adc.enable_peak_hold(source=0)                                                          # Enable peak hold, (source=0) → None
                                                                                         # tracks AD2 min/max; re-enabling resets
lo, hi = adc.read_peak()                                                                # Read peak hold, () → (int, int) 19-bit
print("ad2=", adc2, "lpf=", lpf, "rms=", rms, "peak=", lo, hi)

adc.start_counter(gate_preload=0)                                                       # Start frequency counter, (gate_preload=0) → None
                                                                                         # enables the CNT buffer and the counter
cta, ctb, ctc, overflow = adc.read_counter()                                            # Read counter, () → (int, int, int, bool)
                                                                                         # waits for CTF; overflow=True means the measurement is invalid
print("counter=", cta, ctb, ctc, overflow)

adc.enable_interrupts(0x04)                                                             # Enable interrupts, (mask) → None
                                                                                         # AD1IE: DO goes high while CS is high and AD1F is set
flags = adc.read_flags()                                                                # Read and clear INTF, () → int
                                                                                         # bit 2 = AD1F
print("flags=", flags)

adc.reset()                                                                              # Software reset, () → None
                                                                                         # writes 0x60 to R37 and re-applies the Minimal defaults
