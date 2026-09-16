"""ADE7953 minimal example — read primary single-phase metering values.

Hardware setup: ADE7953 wired to the host over I²C at the chip's fixed
address 0x38. Voltage divider and current sensor (CT, shunt or Rogowski
coil) wired to the analog inputs.

The two calibration constants are design-specific — see the ADE7953 spec's
"Overview" section for how to derive them from the external front end.
"""

from periph.connection.i2c_micropython import I2CConnection
from periph.chips.power.ade7953 import ADE7953Minimal
import time


# I²C address is fixed at 0x38 (no address pins on the ADE7953).
I2C_ADDR = 0x38

# Calibration constants — must match the external front end:
#   voltage_gain = real V (mains) per V at VP–VN   (inverse of divider ratio)
#   current_gain = real A per V at IAP–IAN         (CT + burden, shunt, ...)
VOLTAGE_GAIN = 251.0      # example: 230 V mains over a 1:251 divider
CURRENT_GAIN = 30.0       # example: CT + burden producing 30 A per volt


def main():
    connection = I2CConnection(I2C_ADDR)
    ade = ADE7953Minimal(connection, VOLTAGE_GAIN, CURRENT_GAIN)   # Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    while True:
        v = ade.voltage()                                          # Read bus voltage, () → float V
        i = ade.current()                                          # Read load current, () → float A
        p = ade.active_power()                                     # Read active power, () → float W
        e = ade.active_energy()                                    # Read active energy, () → float Wh
        print('V={:.2f}  I={:.3f}  P={:.2f}  E={:.4f}'.format(v, i, p, e))
        time.sleep(1)


main()