from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.mcp4728 import MCP4728Full

transport = I2CTransport(bus=1, addr=0x60)          # Create I2C transport, (bus=1, addr=0x60)
dac = MCP4728Full(transport)                          # Create MCP4728 driver, (transport)

dac.set_voltage(0, 0.75)                              # Set channel A as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → None
                                                      # writes channel A only via Multi-Write, V_REF=external, gain=×1
dac.set_raw(2, 3000)                                  # Set channel C raw 12-bit code, (channel=0–3, code=0–4095) → None
                                                      # writes channel C only via Multi-Write; clamps to [0, 4095]
dac.set_all([0.1, 0.2, 0.3, 0.4])                    # Update all four channels simultaneously, (fractions[4]) → None
                                                      # single 8-byte Fast Write transaction; EEPROM unaffected
dac.set_voltage_eeprom(0, 0.5, vref=0, gain=1)        # Set channel A and persist to EEPROM, (channel=0–3, fraction, vref=0/1, gain=1/2) → None
                                                      # Single Write updates DAC register and nonvolatile EEPROM
dac.set_raw_eeprom(1, 2048, vref=0, gain=1)           # Set channel B raw code and persist, (channel=0–3, code, vref=0/1, gain=1/2) → None
                                                      # Single Write; takes up to 50 ms for EEPROM write
dac.set_all_eeprom([0.0, 0.25, 0.5, 0.75],            # Update all four channels + EEPROM, (fractions[4], vrefs[4], gains[4]) → None
                  [0, 0, 0, 0],                       # each channel uses external V_DD
                  [1, 1, 1, 1])                       # and gain ×1
                                                      # Sequential Write from A to D; persists all four at the end
dac.set_vref(0, 0, 0, 0)                              # Set V_REF for all four channels, (vref_a, vref_b, vref_c, vref_d) → None
                                                      # 0 = external V_DD; volatile register only
dac.set_gain(1, 1, 1, 1)                              # Set gain for all four channels, (gain_a, gain_b, gain_c, gain_d) → None
                                                      # 1 = ×1, 2 = ×2; volatile register only
dac.set_power_down(0, 0, 0, 0)                        # Set power-down for all four channels, (pd_a, pd_b, pd_c, pd_d) → None
                                                      # 0 = normal, 1 = 1 kΩ, 2 = 100 kΩ, 3 = 500 kΩ to GND
state = dac.read()                                    # Read all four channels' DAC and EEPROM state, () → list[dict]
                                                      # 4 dicts (one per channel) with code, vref, gain, power_down, eeprom_*
print(state[0]['code'])
print(state[0]['eeprom_ready'])
dac.software_update()                                 # Latch all V_OUT simultaneously, () → None
                                                      # General Call 0x00, 0x08; equivalent to LDAC pin pulse
dac.wake_up()                                         # Clear all PD bits via General Call Wake-Up, () → None
                                                      # sends 0x00, 0x09; clears power-down on all four channels
dac.reset()                                           # Reload EEPROM into all DAC registers, () → None
                                                      # General Call 0x00, 0x06; triggers internal POR
ready = dac.is_eeprom_ready()                         # Check if EEPROM write is complete, () → bool
                                                      # True when any pending EEPROM write has finished
print(ready)
