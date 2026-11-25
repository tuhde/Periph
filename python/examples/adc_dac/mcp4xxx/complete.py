from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.mcp4xxx import Mcp4xxxFull

connection = I2CConnection(0x2C)
# MCP4651: dual 8-bit potentiometer (volatile); the non-volatile calls at the end need e.g. MCP4661.
pot = Mcp4xxxFull(connection, part='MCP4651', r_ab=10000.0, r_wiper=75.0)  # Create MCP4XXX driver, (connection, part='MCP4532', r_ab=10000 Ω, r_wiper=75 Ω, hvc=None, hvc_settle_ms=1) → Mcp4xxxFull

# --- Part properties ---
print(pot.channels())                                          # Number of networks, () → int 1-2
                                                               # property of the selected part
print(pot.steps())                                             # Number of wiper positions, () → int 129 or 257
                                                               # max code + 1
print(pot.is_potentiometer())                                  # Terminal A on a pin, () → bool
                                                               # True for the X1 parts
print(pot.is_nonvolatile())                                    # Has EEPROM, () → bool
                                                               # True for 454X / 456X / 464X / 466X

# --- Wiper position and resistance ---
pot.set_wiper(100, 0)                                          # Set wiper, (code 0-256, channel=0) → None
                                                               # writes [(0 << 4) | D8, code & 0xFF]; code clamped
print(pot.get_wiper(0))                                        # Read wiper, (channel=0) → int 0-256
                                                               # two-byte read of the wiper register
pot.set_both(10, 20)                                           # Set both wipers, (code0, code1) → None
                                                               # dual parts only; two transactions
pot.set_resistance(2200.0, 1)                                  # Set W-B resistance, (ohms, channel=0) → None
                                                               # nearest code of R = N/max*RAB + RW, clamped
print(pot.get_resistance(1))                                   # Read W-B resistance, (channel=0) → float Ω
                                                               # N/max*RAB + RW
print(pot.get_resistance_step())                               # Resistance of one step, () → float Ω
                                                               # RAB / max code

# --- Ratio and W-A resistance ---
pot.set_ratio(0.5, 0)                                          # Set wiper ratio, (ratio 0.0-1.0, channel=0) → None
                                                               # code = round(ratio * max code)
print(pot.get_ratio(0))                                        # Read wiper ratio, (channel=0) → float 0.0-1.0
                                                               # code / max code
pot.set_resistance_wa(3300.0, 0)                               # Set W-A resistance, (ohms, channel=0) → None
                                                               # potentiometer parts only
print(pot.get_resistance_wa(0))                                # Read W-A resistance, (channel=0) → float Ω
                                                               # (max code - N)/max*RAB + RW

# --- Increment / decrement ---
print(pot.increment(8, 0))                                     # Step wiper up, (steps=1, channel=0) → int
                                                               # one continuous transaction of increment commands, saturating
print(pot.decrement(4, 0))                                     # Step wiper down, (steps=1, channel=0) → int
                                                               # same with decrement commands, saturating at 0

# --- Shutdown and terminals ---
pot.shutdown(1)                                                # Shut network down, (channel=0) → None
                                                               # TCON.RxHW = 0: A open, W tied to B; wiper kept
print(pot.is_shutdown(1))                                      # Read shutdown state, (channel=0) → bool
                                                               # from TCON
pot.resume(1)                                                  # Leave shutdown, (channel=0) → None
                                                               # TCON.RxHW = 1
pot.set_terminals(a=True, w=False, b=True, channel=0)          # Connect terminals, (a=True, w=True, b=True, channel=0) → None
                                                               # TCON.RxA / RxW / RxB, other bits preserved
print(pot.get_terminals(0))                                    # Read terminals, (channel=0) → (a, w, b)
                                                               # from TCON
pot.set_terminals(channel=0)                                   # Reconnect all terminals, (a=True, w=True, b=True, channel=0) → None
                                                               # restores the wiper terminal
print(pot.get_tcon())                                          # Read TCON, () → int 0-0x1FF
                                                               # raw terminal control register
pot.set_general_call(True)                                     # Set General Call, (enabled) → None
                                                               # TCON.GCEN; chip answers address 0x00 commands

# --- Raw access, model, reset ---
print(pot.read_register(0x04))                                 # Raw read, (address 0-15) → int 0-0x1FF
                                                               # nine-bit value of any address the part has
pot.write_register(0x04, 0x1FF)                                # Raw write, (address, value) → None
                                                               # volatile wipers, TCON (and EEPROM addresses on NV parts)
pot.set_rab(10000.0, 75.0)                                     # Set resistance model, (r_ab Ω, r_wiper=None Ω) → None
                                                               # no I²C traffic; affects conversions only
pot.reset()                                                    # Emulate power-on reset, () → None
                                                               # mid-scale (or EEPROM wiper) in every wiper, TCON 0x1FF

# --- Non-volatile parts only (MCP454X / 456X / 464X / 466X) ---
# print(pot.read_status())                # Read STATUS, () → dict
# pot.wait_ready(timeout_ms=20)           # Wait for the EEPROM write cycle, (timeout_ms=20 ms) → None
# print(pot.get_nv_wiper(0))              # Read EEPROM wiper, (channel=0) → int
# pot.set_nv_wiper(64, 0)                 # Write EEPROM wiper, (code, channel=0) → None
# print(pot.store_wiper(0))               # Store current wiper, (channel=0) → int
# print(pot.read_data(0))                 # Read EEPROM register, (index 0-9) → int 0-0x1FF
# pot.write_data(0, 0x155)                # Write EEPROM register, (index 0-9, value 0-0x1FF) → None
# print(pot.is_wiper_locked(0))           # Read WiperLock, (channel=0) → bool
# print(pot.is_write_protected())         # Read write protect, () → bool
# pot.set_wiper_lock(True, 0)             # High-voltage command, (locked, channel=0) → None (needs hvc pin)
# pot.set_write_protect(True)             # High-voltage command, (enabled) → None (needs hvc pin)
