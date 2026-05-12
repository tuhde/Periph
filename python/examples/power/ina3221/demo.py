from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina3221 import INA3221Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA3221Full(transport)                         # Create INA3221 driver, (transport, r_shunt=0.1 Ω)

# --- Monitor three rails simultaneously ---
# User wires CH1 to 5V rail, CH2 to 3.3V rail, CH3 to 12V rail.
# The demo prints a one-line tabular update each second for 30 seconds.
print('%-8s %-8s %-8s | %-8s %-8s %-8s | %-8s %-8s %-8s' % ('V1', 'I1', 'P1', 'V2', 'I2', 'P2', 'V3', 'I3', 'P3'))
for t in range(30):
    row = []
    for ch in (1, 2, 3):
        v = ina.voltage(ch)                           # Read bus voltage, (channel) → float V
        i = ina.current(ch)                           # Read load current, (channel) → float A
        p = ina.power(ch)                              # Read power, (channel) → float W
        row.extend([v, i, p])
    print('%-8.3f %-8.4f %-8.4f | %-8.3f %-8.4f %-8.4f | %-8.3f %-8.4f %-8.4f' % tuple(row))

    if t == 9:
        # --- Arm critical-alert limits at 1.5x current draw ---
        # Uses per-conversion shunt voltage comparison for fast response.
        for ch in (1, 2, 3):
            i = ina.current(ch)
            ina.set_critical_alert(ch, i * 1.5)
        print('alerts armed')

    if t == 19:
        # --- Arm shunt-voltage summation across all three channels ---
        # The sum limit is set assuming similar shunt resistance on all channels.
        ina.set_summation_channels([1, 2, 3], 0.3)   # Set summation channels, (channels, limit_v) → None
                                                     # configures SCC bits and sum limit register
        print('summation armed')

    time.sleep(1)

# --- Dump alert flags and decode any that fired ---
flags = ina.alert_flags()                              # Read alert flags, () → int
                                                     # reads Mask/Enable register, clears latched flags
print('Mask/Enable: 0x%04X' % flags)
alert_names = ['CF1', 'CF2', 'CF3', 'SF', 'WF1', 'WF2', 'WF3', 'PVF', 'TCF', 'CVRF']
alert_bits = [0x0200, 0x0100, 0x0080, 0x0040, 0x0020, 0x0010, 0x0008, 0x0004, 0x0002, 0x0001]
fired = [name for name, bit in zip(alert_names, alert_bits) if flags & bit]
if fired:
    print('Flags fired:', ', '.join(fired))
else:
    print('No alert flags fired')