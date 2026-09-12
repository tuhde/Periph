import time
from periph.connection.hx711_auto import HX711Connection
from periph.chips.adc_dac.hx710a import HX710AFull

# Temperature-monitored load cell demo: tare at startup, then print weight
# continuously, sampling the on-chip temperature sensor periodically.
# Replace SCALE_FACTOR with the value calibrated for your load cell and V_DD.
# Calibration: (1) call tare() with nothing on the scale; (2) place a known
# 100 g reference weight; (3) SCALE_FACTOR = (read_average() - get_offset()) / 100.
SCALE_FACTOR = 420.0   # ADC counts per gram, calibrated with a 100 g reference weight

connection = HX711Connection(5, 6)        # Create HX711 transport connection, (dout, pd_sck)
chip = HX710AFull(connection)                    # Create HX710A driver — discards first conversion, (connection)

# --- Tare the scale before use ---
# Averaging 10 readings with nothing on the scale suppresses noise in the
# zero-offset capture, so later weight readings aren't skewed by drift.
print('Taring — keep scale empty...')
chip.tare(10)                                   # Capture zero offset from 10-reading average, (times=10) → None
chip.set_scale(SCALE_FACTOR)                    # Set calibration scale factor, (factor: float) → None
print('Tare done. Place weight on scale.')

prev_weight = None
iteration = 0
while True:
    weight = chip.read_weight(3)                # Return calibrated weight, (times=3) → float
    weight_rounded = round(weight, 1)
    if prev_weight is None or abs(weight_rounded - prev_weight) > 1.0:
        print('→ {:.1f} g'.format(weight_rounded))
        prev_weight = weight_rounded

    if iteration % 10 == 0:
        # --- Sample the on-chip temperature sensor every ~5 s ---
        # This is an uncalibrated raw ADC code (~20.4 LSB/°C, chip-to-chip
        # offset/gain vary per the datasheet), intended only for the
        # datasheet's stated purpose of relative drift compensation of the
        # weight reading — not as an absolute °C measurement.
        temp_raw = chip.read_temperature_raw()  # Read raw on-chip temperature code, () → int
        print('→ {:.1f} g (temp raw={})'.format(weight_rounded, temp_raw))

    iteration += 1
    time.sleep_ms(500)
