from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.pcf8591 import PCF8591Full
import time

transport = I2CTransport(bus=1, addr=0x48)          # Create I2C transport, (bus, addr)
adc = PCF8591Full(transport)                         # Create PCF8591 driver, (transport)

# --- Wire a potentiometer across VAGND–VREF with the wiper to AIN0 ---
# Connect an LED (with series resistor) to AOUT. In a loop, read AIN0, map
# the 0–255 value to a DAC output value, and write it to AOUT — the LED
# brightness tracks the potentiometer. This demonstrates the ADC→DAC
# feedback path inside a single chip.

VREF  = 3.3
VAGND = 0.0

adc.configure(PCF8591Full.MODE_4_SINGLE_ENDED, False, True)   # Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                # single-ended mode with DAC output enabled

for n in range(20):
    raw = adc.read_channel(0)                        # Read single channel, (channel=0–3) → int
    vin  = VAGND + raw * (VREF - VAGND) / 256.0
    adc.set_dac(raw)                                 # Enable DAC and set raw value, (value=0–255) → None
    vout = VAGND + raw * (VREF - VAGND) / 256.0
    print('n={:2d} raw={:3d} vin={:.3f} V  vout={:.3f} V'.format(n, raw, vin, vout))
    time.sleep(0.2)
