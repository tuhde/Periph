from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ad5248 import AD5248Full

connection = I2CConnection(0x2C)
pot = AD5248Full(connection, r_ab=10000.0, r_wiper=160.0)      # Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω) → AD5248Full

# --- Wiper position ---
pot.set_wiper(0, 100)                                          # Set wiper, (channel 0-1, code 0-255) → None
                                                               # writes [channel << 7, code]; code clamped to 0-255
print(pot.get_wiper(0))                                        # Read wiper, (channel 0-1) → int 0-255
                                                               # sends the instruction byte alone, then reads one byte
pot.set_both(10, 20)                                           # Set both wipers, (code1 0-255, code2 0-255) → None
                                                               # two transactions, one per channel

# --- Resistance ---
pot.set_resistance(1, 2200.0)                                  # Set W-B resistance, (channel 0-1, ohms) → None
                                                               # nearest code of R = code/256*RAB + 2*RW, clamped
print(pot.get_resistance(1))                                   # Read W-B resistance, (channel 0-1) → float Ω
                                                               # code/256*RAB + 2*RW

# --- Ratio and increment ---
pot.set_ratio(0, 0.5)                                          # Set wiper ratio, (channel 0-1, ratio 0.0-1.0) → None
                                                               # code = round(ratio*256) clamped to 255; needs terminal A wired
print(pot.get_ratio(0))                                        # Read wiper ratio, (channel 0-1) → float 0.0-1.0
                                                               # code / 256
print(pot.increment(0, 5))                                     # Step wiper, (channel 0-1, steps=1) → int 0-255
                                                               # read-modify-write, clamped to 0-255; negative steps decrement

# --- Shutdown ---
pot.shutdown(1)                                                # Shut channel down, (channel 0-1) → None
                                                               # opens terminal A, shorts wiper to B, RDAC kept
print(pot.is_shutdown(1))                                      # Query driver-tracked state, (channel 0-1) → bool
                                                               # the chip cannot be read back
pot.resume(1)                                                  # Leave shutdown, (channel 0-1) → None
                                                               # rewrites the RDAC with SD = 0

# --- Model and reset ---
pot.set_rab(10000.0, 160.0)                                    # Set resistance model, (r_ab Ω, r_wiper=None Ω) → None
                                                               # no I²C traffic; affects conversions only
pot.reset()                                                    # Reset to power-up preset, () → None
                                                               # writes midscale 0x80 to both channels with SD = 0
