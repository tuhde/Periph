import time

from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.cs43l22 import CS43L22Minimal

connection = I2CConnection(bus=1, addr=0x4A)                                # Create I2C connection, (bus=1, addr=0x4A)
dac = CS43L22Minimal(connection)                                            # Create CS43L22 driver and run the init sequence, (connection, res_pin=None)
dac.start()                                                                 # Power up the DAC once MCLK is running, () → None
dac.set_volume(-20.0)                                                       # Set master volume, (db=-102–12 dB, 0.5 dB steps) → None
dac.mute(False)                                                             # Unmute the headphone outputs, (on=True) → None

for _ in range(5):
    print(dac.get_volume())                                # Read back master volume, () → float dB
    time.sleep(1)

dac.stop()                                                                  # Power down the DAC, () → None
