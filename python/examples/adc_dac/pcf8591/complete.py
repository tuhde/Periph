from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.pcf8591 import PCF8591Full

transport = I2CTransport(bus=1, addr=0x48)          # Create I2C transport, (bus, addr)
adc = PCF8591Full(transport)                         # Create PCF8591 driver, (transport)

ch0_raw = adc.read_channel(0)                        # Read single channel, (channel=0–3) → int
                                                     # discards the stale first conversion byte; returns 0–255
ch1_raw = adc.read_channel(1)                        # Read single channel, (channel=0–3) → int
                                                     # selects channel 1 via the control byte, returns 0–255
all_raw = adc.read_all()                             # Read all four channels, () → list[int]
                                                     # sets AI=1 and reads 5 bytes; discards stale byte 0

v0 = adc.read_channel_voltage(0, 3.3, 0.0)           # Read channel as voltage, (channel, vref=3.3 V, vagnd=0.0 V) → float V
                                                     # converts raw to voltage using V_AGND + raw × (V_REF−V_AGND) / 256
v_all = adc.read_all_voltage(3.3, 0.0)               # Read all channels as voltages, (vref=3.3 V, vagnd=0.0 V) → list[float] V
                                                     # returns four voltages using the same conversion

adc.configure(PCF8591Full.MODE_3_DIFFERENTIAL, False, False)  # Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                # sets AIP=01 (3 differential channels vs AIN3) and clears AOE/AI
diff = adc.read_differential(0)                       # Read differential channel, (channel=0–2) → int
                                                     # returns signed 8-bit two's complement (-128 to 127)
adc.configure(PCF8591Full.MODE_4_SINGLE_ENDED, False, True)   # Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                # restores 4 single-ended mode and enables the DAC output
adc.set_dac(128)                                     # Enable DAC and set raw value, (value=0–255) → None
                                                     # sets AOE=1 and writes 128 to the DAC register; V_AOUT ≈ V_REF/2
adc.set_dac_voltage(0.25)                            # Set DAC as fraction of (VREF−VAGND), (fraction=0.0–1.0) → None
                                                     # maps fraction to 0–255 and writes the DAC; AOUT follows
adc.disable_dac()                                    # Disable DAC output, () → None
                                                     # clears AOE; AOUT returns to high-impedance
