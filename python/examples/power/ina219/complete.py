from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina219 import INA219Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA219Full(transport)

print(ina.voltage())                                  # Read bus voltage, () → float V
print(ina.shunt_voltage())                            # Read shunt voltage, () → float V
print(ina.current())                                  # Read load current, () → float A
print(ina.power())                                    # Read power, () → float W
print(ina.conversion_ready())                         # Check conversion done, () → bool
print(ina.overflow())                                 # Check math overflow, () → bool

ina.configure(brng=1, pga=3, badc=0x03, sadc=0x03, mode=7)
                                                     # Configure ADC, (brng 0–1, pga 0–3, badc 0x0F, sadc 0x0F, mode 0–7) → None
                                                     # sets bus range, PGA gain, ADC resolution, and operating mode

ina.shutdown()                                       # Put chip into power-down mode, () → None
time.sleep_ms(1)
ina.wake()                                           # Restore previous operating mode, () → None

ina.reset()                                          # Reset all registers and re-write calibration, () → None
