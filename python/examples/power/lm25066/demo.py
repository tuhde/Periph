import sys
from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.lm25066 import LM25066Full
import time

connection = I2CConnection(0x40)                                       # Create I²C connection, (addr=0x40) → Connection
hotswap = LM25066Full(connection, r_sense_mohm=1.0, current_limit_high=False)  # Create LM25066 driver, 12 V rail with 1 mΩ sense resistor

print(hotswap.manufacturer_id(), hotswap.model(), hotswap.revision())  # Identify the chip, () → str each

hotswap.clear_faults()                                                 # Release SMBA after power-up, () → None

# Hot-swap rail monitor: one line per second for a minute
for i in range(60):
    if i == 30:
        hotswap.set_averaging(6)                                       # 64-sample averaging, (exponent) → None
        hotswap.set_vin_uv_warn(10.0)                                  # VIN under-voltage warning at 10 V, (volts) → None
        hotswap.set_iin_warn(20.0)                                     # Input over-current warning at 20 A, (amps) → None
        hotswap.enable_interrupt(LM25066Full.SOURCE_VIN_UV_WARN | LM25066Full.SOURCE_IIN_WARN)  # Unmask both alerts, (source) → None
    print('VIN %.2f V  VOUT %.2f V  IIN %.3f A (avg %.3f)  PIN %.2f W  T %.1f C' % (
        hotswap.voltage_in(), hotswap.voltage_out(), hotswap.current(),
        hotswap.current_avg(), hotswap.power(), hotswap.temperature()))
    time.sleep(1)

print('peak power %.2f W' % hotswap.power_peak())                      # Peak input power, () → float W
print('diagnostic 0x%04X' % hotswap.poll_interrupt())                  # Alert flags, () → int
hotswap.clear_faults()                                                 # Finish servicing, () → None

if '--control' in sys.argv:
    hotswap.operation_off()                                            # CUTS THE LOAD RAIL, () → None
    time.sleep(1)
    hotswap.operation_on()                                             # Switch the MOSFET back on, () → None
