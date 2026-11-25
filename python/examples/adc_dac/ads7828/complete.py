from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ads7828 import ADS7828Full

connection = I2CConnection(bus=1, addr=0x48)          # Create I2C connection, (bus, addr)
adc = ADS7828Full(connection)                         # Create ADS7828 driver, (connection, vref=None → internal 2.5 V)
                                                      # enables the internal reference and waits 1 ms for it to settle

raw = adc.read_raw(3)                                # Read CH3 raw code, (channel=0–7) → int
                                                     # single-ended, interleaved channel encoding; returns 0–4095
volts = adc.read_voltage(3)                          # Read CH3 voltage, (channel=0–7) → float V
                                                     # raw / 4096 × vref
all_v = adc.read_all()                               # Read all eight channels, () → list[float] V
                                                     # one conversion per channel, CH0…CH7

draw = adc.read_differential_raw(0)                  # Read pair 0 raw code, (pair=0–3, inverted=False) → int
                                                     # CH0 (+) / CH1 (−); a negative input difference reads 0
dv = adc.read_differential(1, True)                  # Read pair 1 inverted, (pair=0–3, inverted=False) → float V
                                                     # CH3 (+) / CH2 (−)
sv = adc.read_signed_differential(0)                 # Read pair 0 signed, (pair=0–3) → float V
                                                     # two conversions (normal and inverted), range −vref … +vref

lsb = adc.lsb()                                      # Get code step, () → float V
                                                     # vref / 4096 (610 µV at 2.5 V)
scan = adc.read_scan([0, 2, 4, 6])                   # Scan channels, (channels=list[int]) → list[int]
                                                     # one raw conversion per channel in the given order

adc.set_reference(5.0)                               # Use external reference, (vref=None|float V) → None
                                                     # PD1 = 0 from now on; the reference must already be stable
ext = adc.get_reference()                            # Get reference voltage, () → float V
internal = adc.uses_internal_reference()             # Internal reference selected?, () → bool
adc.set_reference(None)                              # Use internal reference, (vref=None|float V) → None
                                                     # re-runs the reference turn-on and 1 ms settle wait

adc.set_power_mode(ADS7828Full.REF_ON_ADC_ON)        # Set PD bits, (pd=0–3) → None
                                                     # internal reference requires PD1 = 1 (values 2 or 3)
adc.power_down()                                     # Power down completely, () → None
                                                     # lone command with PD=00; next read re-enables the reference
