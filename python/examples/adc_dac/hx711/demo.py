import time
from machine import Pin
from periph.transport.hx711_micropython import HX711Transport
from periph.chips.adc_dac.hx711 import HX711Full

# Kitchen scale demo: tare at startup, then print weight continuously.
# Replace SCALE_FACTOR with the value calibrated for your load cell and V_DD.
# Calibration: (1) call tare() with nothing on the scale; (2) place a known
# weight W grams; (3) SCALE_FACTOR = (read_average() - get_offset()) / W.
SCALE_FACTOR = 420.0   # ADC counts per gram (example, calibrate for your setup)

dout   = Pin(5, Pin.IN)
pd_sck = Pin(6, Pin.OUT)
transport = HX711Transport(dout, pd_sck)        # Create HX711 transport, (dout, pd_sck)
chip = HX711Full(transport)                     # Create HX711 driver — discards first conversion, (transport)

print('Taring — keep scale empty...')
chip.tare(10)                                   # Capture zero offset from 10-reading average, (times=10) → None
chip.set_scale(SCALE_FACTOR)                    # Set calibration scale factor, (factor: float) → None
print('Tare done. Place weight on scale.')

prev_weight = None
while True:
    weight = chip.read_weight(3)                # Return calibrated weight, (times=3) → float
    weight_rounded = round(weight, 1)
    if prev_weight is None or abs(weight_rounded - prev_weight) > 1.0:
        print('→ {:.1f} g'.format(weight_rounded))
        prev_weight = weight_rounded
    time.sleep_ms(500)
