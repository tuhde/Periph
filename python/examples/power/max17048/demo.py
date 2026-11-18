from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.max17048 import MAX17048Full
import sys
import time

connection = I2CConnection(0x36)                                       # Create I²C connection, (addr=0x36) → Connection
gauge = MAX17048Full(connection, cells=1)                              # Create MAX17048 driver, single Li-ion/LiPo cell

print('version 0x%04X, id 0x%02X' % (gauge.version(), gauge.chip_id()))
gauge.clear_status(MAX17048Full.SOURCE_RESET)                          # Clear the reset indicator, (mask) → None

# Battery fuel gauge: one line per second for 30 s
for _ in range(30):
    print('%.3f V  %.1f %%  %+.1f %%/h' % (gauge.voltage(), gauge.percent(), gauge.charge_rate()))
    time.sleep(1)

gauge.set_alert_soc(10)                                                # Low-SOC alert at 10 %, (percent) → None
gauge.set_voltage_alert(3.3, 4.25)                                     # Voltage window 3.3-4.25 V, (min_v, max_v) → None
gauge.on_interrupt(lambda status: print('alert', hex(status)))         # Print decoded alert sources, (callback, int_pin=None) → None
time.sleep(5)
gauge.off_interrupt()
print('poll_interrupt:', hex(gauge.poll_interrupt()))                  # Without an ALRT pin, () → int

for celsius in (5.0, 35.0):
    gauge.compensate_temperature(celsius)                              # RCOMP for the temperature, (celsius) → None
    print('RCOMP at %.0f C: 0x%02X' % (celsius, gauge.get_rcomp()))
gauge.compensate_temperature(20.0)

gauge.disable_hibernate()                                              # Never hibernate, () → None
print('hibernating:', gauge.is_hibernating())
gauge.set_hibernate_thresholds(26.624, 0.06)                           # Back to the defaults

gauge.enable_sleep(True)                                               # Sleep round trip, (enabled) → None
gauge.sleep()
time.sleep(1)
gauge.wake()
gauge.enable_sleep(False)
print('after wake: %.3f V' % gauge.voltage())

if '--quick-start' in sys.argv:
    gauge.quick_start()                                                # Restart the SOC estimate, () → None
    time.sleep(1)
    print('SOC after quick start: %.1f %%' % gauge.percent())
