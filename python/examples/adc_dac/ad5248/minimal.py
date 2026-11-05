from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ad5248 import AD5248Minimal
import time

connection = I2CConnection(0x2C)
pot = AD5248Minimal(connection, r_ab=10000.0, r_wiper=160.0)  # Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω) → AD5248Minimal

pot.set_wiper(0, 64)                                           # Set channel 0 wiper, (channel 0-1, code 0-255) → None
pot.set_resistance(1, 2200.0)                                  # Set channel 1 W-B resistance, (channel 0-1, ohms) → None

while True:
    code = pot.get_wiper(0)                                    # Read channel 0 wiper, (channel 0-1) → int 0-255
    ohms = pot.get_resistance(0)                               # Read channel 0 W-B resistance, (channel 0-1) → float Ω
    print('ch0 code=%d  R_WB=%.0f ohm' % (code, ohms))
    time.sleep(1)
