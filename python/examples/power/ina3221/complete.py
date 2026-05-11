from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina3221 import INA3221Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA3221Full(transport)                         # Create INA3221 driver, (transport, r_shunt=0.1 Ω)

for ch in (1, 2, 3):
    v = ina.voltage(ch)                               # Read bus voltage, (channel) → float V
                                                     # left-aligned 12-bit bus register, 8 mV LSB
    sv = ina.shunt_voltage(ch)                        # Read shunt voltage, (channel) → float V
                                                     # left-aligned 13-bit signed shunt, 40 µV LSB
    i = ina.current(ch)                               # Read load current, (channel) → float A
                                                     # computed from shunt voltage / r_shunt
    p = ina.power(ch)                                 # Read power, (channel) → float W
                                                     # computed from voltage × current

print(hex(ina.manufacturer_id()))                     # Read Manufacturer ID, () → int 0x5449
                                                     # Texas Instruments ID
print(hex(ina.die_id()))                              # Read Die ID, () → int 0x3220
                                                     # INA3221 die revision

ok = ina.conversion_ready()                           # Check conversion done, () → bool
                                                     # reads CVRF bit from Mask/Enable register
print(ok)

ina.configure(avg=4, vbus_ct=4, vsh_ct=4, mode=7)    # Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → None
                                                     # sets averaging count, conversion time, and operating mode

ina.enable_channel(1, True)                           # Enable channel, (channel, enabled) → None
                                                     # modifies CH1en bit in Configuration register
ena = ina.channel_enabled(1)                          # Read channel enabled, (channel) → bool
                                                     # reads CH1en bit

ina.set_critical_alert(1, 0.1)                        # Set critical alert, (channel, limit_v, latch=False) → None
                                                     # per-conversion threshold on shunt voltage
ina.set_warning_alert(2, 0.05)                        # Set warning alert, (channel, limit_v, latch=False) → None
                                                     # per-average threshold on shunt voltage

flags = ina.alert_flags()                              # Read alert flags, () → int
                                                     # reads Mask/Enable register, clears latched flags

ina.set_summation_channels([1, 2], 0.2)              # Set summation channels, (channels, limit_v) → None
                                                     # enables SCC bits and sets sum limit register
sv_sum = ina.summation_value()                        # Read summation value, () → float V
                                                     # reads Shunt-Voltage Sum register

ina.set_power_valid_limits(5.5, 4.5)                 # Set PV limits, (upper_v, lower_v) → None
                                                     # sets PV Upper/Lower Limit registers
pv = ina.power_valid()                                # Read power valid, () → bool
                                                     # reads PVF bit from Mask/Enable

ina.shutdown()                                        # Put chip into power-down mode, () → None
                                                     # saves current mode for wake()
time.sleep_ms(1)
ina.wake()                                            # Restore operating mode, () → None
                                                     # restores the mode saved by shutdown()

ina.reset()                                           # Reset all registers, () → None
                                                     # sets RST bit, chip re-initializes to defaults