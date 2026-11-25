from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ad5248 import AD5248Full
import time

connection = I2CConnection(0x2C)
pot = AD5248Full(connection, r_ab=10000.0, r_wiper=160.0)      # Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω) → AD5248Full

# --- Sweep channel 0 across its full range ---
# Programmable gain / level trim: step the wiper from code 0 to 255 in steps of 16
# and print the resulting W-B resistance. Zero scale is not 0 Ω (about 2*RW = 320 Ω).
for code in range(0, 256, 16):
    pot.set_wiper(0, code)                                     # Set channel 0 wiper, (channel 0-1, code 0-255) → None
    ohms = pot.get_resistance(0)                               # Read W-B resistance, (channel 0-1) → float Ω
    print('code=%3d  R_WB=%7.1f ohm' % (code, ohms))
    time.sleep(0.1)

# --- Set channel 1 to 2.2 kΩ and report the quantisation error ---
# 256 positions over 10 kΩ give a step of about 39 Ω, so the result is the nearest code.
pot.set_resistance(1, 2200.0)                                  # Set channel 1 to 2.2 kΩ, (channel 0-1, ohms) → None
actual = pot.get_resistance(1)                                 # Read back W-B resistance, (channel 0-1) → float Ω
print('ch1 target=2200 ohm  actual=%.1f ohm  error=%.1f ohm' % (actual, actual - 2200.0))

# --- Shutdown and resume channel 1 ---
# Shutdown opens terminal A and shorts the wiper to B (about 0 Ω) without changing the RDAC.
pot.shutdown(1)                                                # Shut channel 1 down, (channel 0-1) → None
print('ch1 shutdown=%s  code=%d' % (pot.is_shutdown(1), pot.get_wiper(1)))  # Query state, (channel 0-1) → bool
time.sleep(1)
pot.resume(1)                                                  # Resume channel 1, (channel 0-1) → None

# --- Restore the power-up preset ---
pot.reset()                                                    # Reset both channels to midscale, () → None
print('after reset: ch0=%d ch1=%d' % (pot.get_wiper(0), pot.get_wiper(1)))
