from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.mcp4xxx import Mcp4xxxFull
import time

PART = 'MCP4532'   # any of MCP4531 ... MCP4662
connection = I2CConnection(0x58)
pot = Mcp4xxxFull(connection, part=PART, r_ab=10000.0, r_wiper=75.0)  # Create MCP4XXX driver, (connection, part='MCP4532', r_ab=10000 Ω, r_wiper=75 Ω, hvc=None, hvc_settle_ms=1) → Mcp4xxxFull

# --- Identify the family member ---
# One driver covers sixteen parts; the descriptor tells resolution, networks and memory type.
print(PART, pot.channels(), 'network(s)', pot.steps(), 'steps',      # Part properties, () → int
      'pot' if pot.is_potentiometer() else 'rheostat',
      'EEPROM' if pot.is_nonvolatile() else 'RAM')
print('power-up wiper', pot.get_wiper(), pot.get_resistance(), 'ohm')  # Read wiper, (channel=0) → int

# --- Set 2.2 kΩ and report the quantisation error ---
# The wiper snaps to the nearest step of RAB / max code.
pot.set_resistance(2200.0)                                     # Set W-B resistance, (ohms, channel=0) → None
actual = pot.get_resistance()                                  # Read W-B resistance, (channel=0) → float Ω
print('target=2200 ohm  code=%d  actual=%.1f ohm  error=%.1f ohm' % (pot.get_wiper(), actual, actual - 2200.0))

# --- Step the wiper up and down ---
# increment / decrement send one command byte per step in a single transaction and
# saturate at full scale and zero scale.
for steps, up in ((8, True), (8, True), (4, False), (10, True)):
    code = pot.increment(steps) if up else pot.decrement(steps)  # Step wiper, (steps=1, channel=0) → int
    print('%s%d -> code=%d  R_WB=%.1f ohm' % ('+' if up else '-', steps, code, pot.get_resistance()))

# --- Ratios ---
# The ratio is the fraction of RAB between W and B (the unloaded divider ratio of a potentiometer).
for ratio in (0.0, 0.25, 0.5, 0.75, 1.0):
    pot.set_ratio(ratio)                                       # Set wiper ratio, (ratio 0.0-1.0, channel=0) → None
    line = 'ratio=%.2f  code=%d  R_WB=%.1f' % (ratio, pot.get_wiper(), pot.get_resistance())
    if pot.is_potentiometer():
        line += '  R_WA=%.1f' % pot.get_resistance_wa()        # Read W-A resistance, (channel=0) → float Ω
    print(line)

# --- Dual parts: drive both networks ---
if pot.channels() == 2:
    pot.set_both(32, 96)                                       # Set both wipers, (code0, code1) → None
    print('both:', pot.get_wiper(0), pot.get_wiper(1), '->', pot.increment(5, 1), pot.decrement(2, 1))

# --- Shutdown and terminal disconnect ---
# Shutdown ties W to B and opens A without losing the wiper; set_terminals(w=False) floats W.
pot.shutdown()                                                 # Shut network down, (channel=0) → None
print('shutdown=%s  code=%d' % (pot.is_shutdown(), pot.get_wiper()))  # Read state, (channel=0) → bool
time.sleep(1)
pot.resume()                                                   # Leave shutdown, (channel=0) → None
pot.set_terminals(w=False)                                     # Disconnect the wiper, (a=True, w=True, b=True, channel=0) → None
print('terminals', pot.get_terminals())                        # Read terminals, (channel=0) → (a, w, b)
pot.set_terminals()                                            # Reconnect all terminals, (a=True, w=True, b=True, channel=0) → None

# --- Non-volatile parts: EEPROM wiper and data ---
# store_wiper makes the setting survive power cycles; every EEPROM write uses one of the
# 1 000 000 write cycles, so do not do this in a loop.
if pot.is_nonvolatile():
    print(pot.read_status())                                   # Read STATUS, () → dict
    print('stored', pot.store_wiper())                         # Store the wiper in EEPROM, (channel=0) → int
    pot.write_data(0, 0x155)                                   # Write calibration marker, (index 0-9, value 0-0x1FF) → None
    print('data0', hex(pot.read_data(0)), 'nv wiper', pot.get_nv_wiper())  # Read back, (index) → int

# --- Back to the power-up state ---
pot.reset()                                                    # Emulate power-on reset, () → None
print('after reset:', pot.get_wiper())
