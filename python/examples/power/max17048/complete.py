from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.max17048 import MAX17048Full
import time

connection = I2CConnection(0x36)                                       # Create I²C connection, (addr=0x36) → Connection
gauge = MAX17048Full(connection, cells=1)                              # Create MAX17048 driver, (connection, cells) → None
                                                                       # cells = 1 (MAX17048) or 2 (MAX17049)

print(gauge.voltage())                                                 # Cell voltage, () → float V
print(gauge.percent())                                                 # State of charge, () → float %
print(hex(gauge.raw_voltage()))                                        # VCELL register, () → int
print(hex(gauge.raw_soc()))                                            # SOC register, () → int
print(gauge.charge_rate())                                             # Charge/discharge rate, () → float %/h
print(hex(gauge.version()))                                            # VERSION register, () → int
print(hex(gauge.chip_id()))                                            # Factory ID, () → int

gauge.set_rcomp(0x97)                                                  # RCOMP, (value) → None
print(gauge.get_rcomp())                                               # () → int
gauge.compensate_temperature(25.0)                                     # RCOMP from the temperature, (celsius) → None

gauge.set_alert_soc(10)                                                # Empty alert threshold, (percent) → None; 1-32 %
print(gauge.get_alert_soc())                                           # () → int %
gauge.set_soc_change_alert(True)                                       # Alert on 1 % SOC change, (enabled) → None
gauge.set_voltage_alert(3.3, 4.25)                                     # Voltage window, (min_v, max_v) → None
                                                                       # 0 / 5.1 V disable the respective limit
print(gauge.get_voltage_alert())                                       # () → (min_v, max_v)
gauge.set_reset_voltage(3.0)                                           # Reset voltage, (volts) → None
print(gauge.get_reset_voltage())                                       # () → float V
gauge.enable_voltage_reset_alert(True)                                 # Voltage reset asserts ALRT, (enabled) → None
gauge.set_comparator_disabled(False)                                   # Hibernate comparator off, (disabled) → None

gauge.set_hibernate_thresholds(26.624, 0.06)                           # Hibernate thresholds, (hib_thr_pct_per_h, act_thr_v) → None
print(gauge.get_hibernate_thresholds())                                # () → (hib_thr_pct_per_h, act_thr_v)
gauge.disable_hibernate()                                              # Never hibernate, () → None
gauge.force_hibernate()                                                # Always hibernate, () → None
gauge.set_hibernate_thresholds(26.624, 0.06)                           # Restore the defaults
print(gauge.is_hibernating())                                          # Hibernate status, () → bool

print(hex(gauge.read_status()))                                        # STATUS register, () → int
gauge.clear_status(MAX17048Full.SOURCE_RESET)                          # Clear flags and ALRT, (mask) → None
print(hex(gauge.read_config()))                                        # CONFIG register, () → int
gauge.write_config(gauge.read_config())                                # Write CONFIG, (value) → None

print(gauge.poll_interrupt())                                          # Alert sources, () → int
                                                                       # SOURCE_RESET / _OVERVOLTAGE / _UNDERVOLTAGE
                                                                       # / _VRESET / _LOW_SOC / _SOC_CHANGE
gauge.enable_interrupt(MAX17048Full.SOURCE_SOC_CHANGE)                 # Enable a source, (source) → None
gauge.disable_interrupt(MAX17048Full.SOURCE_SOC_CHANGE)                # Disable a source, (source) → None
gauge.on_interrupt(lambda status: print('alert', hex(status)))         # Subscribe to ALRT, (callback, int_pin=None) → None
time.sleep(2)
gauge.off_interrupt()                                                  # Unsubscribe, () → None

gauge.enable_sleep(True)                                               # Allow sleep mode, (enabled) → None
gauge.sleep()                                                          # Sleep, () → None
gauge.wake()                                                           # Wake up, () → None
gauge.enable_sleep(False)

gauge.quick_start()                                                    # Restart the SOC estimate, () → None
                                                                       # only if the first SOC is clearly wrong
gauge.reset()                                                          # Power-on reset, () → None; waits 1 s
