import time
from periph.connection.hx711_auto import HX711Connection
from periph.chips.adc_dac.hx710b import HX710BFull

# Battery-powered load cell demo: tare at startup, then print weight
# continuously, watching the DVDD−AVDD supply-difference reading for
# drift that signals a low battery. Replace SCALE_FACTOR with the value
# calibrated for your load cell and wiring topology. Calibration: (1)
# call tare() with nothing on the scale; (2) place a known 100 g reference
# weight; (3) SCALE_FACTOR = (read_average() - get_offset()) / 100.
SCALE_FACTOR = 420.0   # ADC counts per gram, calibrated with a 100 g reference weight
LOW_BATT_DELTA = 50000  # supply-diff drift threshold from baseline that triggers a low-battery warning

connection = HX711Connection(5, 6)        # Create HX711 transport connection, (dout, pd_sck)
chip = HX710BFull(connection)                    # Create HX710B driver — discards first conversion, (connection)

# --- Tare the scale before use ---
# Averaging 10 readings with nothing on the scale suppresses noise in the
# zero-offset capture, so later weight readings aren't skewed by drift.
print('Taring — keep scale empty...')
chip.tare(10)                                   # Capture zero offset from 10-reading average, (times=10) → None
chip.set_scale(SCALE_FACTOR)                    # Set calibration scale factor, (factor: float) → None
print('Tare done. Place weight on scale.')

# --- Capture the supply-difference baseline at full charge ---
# The datasheet gives no absolute LSB-to-volts conversion for this
# channel — it is only useful for relative drift tracking. Capture one
# reading at startup as a "known-good battery" baseline, then compare
# later readings against it to detect discharge.
baseline_supp_diff = chip.read_supply_diff_raw()    # Read raw DVDD−AVDD supply-difference code, () → int

prev_weight = None
iteration = 0
while True:
    weight = chip.read_weight(3)                # Return calibrated weight, (times=3) → float
    weight_rounded = round(weight, 1)
    if prev_weight is None or abs(weight_rounded - prev_weight) > 1.0:
        print('→ {:.1f} g'.format(weight_rounded))
        prev_weight = weight_rounded

    if iteration % 20 == 0:
        # --- Sample the DVDD−AVDD supply-difference channel every ~10 s ---
        # Uncalibrated ADC code, intended only for relative drift tracking
        # against a known-good baseline (the datasheet's stated purpose for
        # this channel in battery-powered weigh-scale applications) — not
        # an absolute voltage reading.
        supp_diff = chip.read_supply_diff_raw() # Read raw DVDD−AVDD supply-difference code, () → int
        drift = supp_diff - baseline_supp_diff
        if abs(drift) > LOW_BATT_DELTA:
            print('LOW BATTERY (supply_diff={}, drift={})'.format(supp_diff, drift))

    iteration += 1
    time.sleep_ms(500)
