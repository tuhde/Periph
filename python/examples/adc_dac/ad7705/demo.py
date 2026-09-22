from machine import Pin, SPI

from periph.chips.adc_dac.ad7705 import AD7705Full
from periph.connection.spi_micropython import SPIConnection

TEMP_COEFF = 0.05
TEMP_REFERENCE = 1.25

spi = SPI(1, baudrate=1_000_000, polarity=1, phase=1)
cs = Pin(15, Pin.OUT, value=1)

conn = SPIConnection(spi, cs)
adc = AD7705Full(conn, vref=2.5, mclk_hz=2_457_600)                                      # Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → None

# --- Configure both channels for the bridge-pressure application ---
# Channel 1 reads the pressure bridge at gain 128 (small mV-level signal);
# Channel 2 reads an auxiliary temperature sensor at gain 2 for temperature
# compensation of the pressure reading.
adc.configure(channel=1, gain=128, bipolar=True, buffered=True, output_rate_hz=50)       # Configure channel 1, (channel=1, gain=1, bipolar=True, buffered=False, output_rate_hz=50) → None
adc.configure(channel=2, gain=2, bipolar=True, buffered=False, output_rate_hz=50)        # Configure channel 2, (channel=1, gain=1, bipolar=True, buffered=False, output_rate_hz=50) → None

# --- Self-calibrate both channels before the measurement loop ---
adc.self_calibrate(channel=1)                                                            # Self-calibrate channel, (channel=1) → None
adc.self_calibrate(channel=2)                                                            # Self-calibrate channel, (channel=1) → None

# --- Sample continuously and compensate the pressure reading for temperature ---
# pressure_compensated = pressure - TEMP_COEFF * (temp - TEMP_REFERENCE)
# Print whenever the compensated reading changes by more than 1 mV.
last_pressure = None
CHANGE_THRESHOLD = 0.001
while True:
    pressure_raw = adc.read_voltage(channel=1)                                           # Read voltage, (channel=1) → float V
    temp = adc.read_voltage(channel=2)                                                   # Read voltage, (channel=2) → float V
    pressure = pressure_raw - TEMP_COEFF * (temp - TEMP_REFERENCE)
    if last_pressure is None or abs(pressure - last_pressure) > CHANGE_THRESHOLD:
        print("→ pressure={:.4f} V (raw {:.4f} V, temp {:.4f} V)".format(pressure, pressure_raw, temp))
        last_pressure = pressure
