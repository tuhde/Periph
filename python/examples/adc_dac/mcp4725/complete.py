from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.mcp4725 import MCP4725Full

transport = I2CTransport(bus=1, addr=0x60)          # Create I2C transport, (bus=1, addr=0x60)
dac = MCP4725Full(transport)                       # Create MCP4725 driver, (transport)

dac.set_voltage(0.75)                               # Set output as fraction of V_DD, (fraction=0.0–1.0) → None
                                                     # converts fraction to 12-bit code and issues Fast Write
dac.set_raw(3000)                                  # Set raw 12-bit code, (code=0–4095) → None
                                                     # clamps to [0, 4095] and writes DAC register only
dac.set_voltage_eeprom(0.5)                         # Set output and persist to EEPROM, (fraction=0.0–1.0) → None
                                                     # writes both DAC register and EEPROM for power-cycle persistence
dac.set_raw_eeprom(2048)                            # Set raw code and persist to EEPROM, (code=0–4095) → None
                                                     # writes both DAC register and EEPROM for power-cycle persistence
state = dac.read()                                  # Read DAC and EEPROM registers, () → dict
                                                     # returns code, voltage_fraction, power_down, eeprom_code, eeprom_power_down, eeprom_ready
print(state['code'])
print(state['voltage_fraction'])
print(state['eeprom_ready'])
dac.set_power_down(MCP4725Full.PD_100K_GND)        # Set power-down mode with code preserved, (mode=0–3) → None
                                                     # enters power-down; output stage disconnects with 100k to GND
dac.wake_up()                                       # Send General Call Wake-Up to clear power-down, () → None
                                                     # sends 0x00, 0x09 to address 0x00; clears PD bits in DAC register
dac.reset()                                         # Send General Call Reset and reload EEPROM, () → None
                                                     # sends 0x00, 0x06; triggers internal POR and reloads DAC from EEPROM
ready = dac.is_eeprom_ready()                       # Check if EEPROM write is complete, () → bool
                                                     # returns True when any pending EEPROM write has finished