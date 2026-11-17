from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.lc709203f import LC709203FFull
import time

connection = I2CConnection(0x0B)                                       # Create I²C connection, (addr=0x0B) → Connection
gauge = LC709203FFull(connection, capacity_mah=1000, profile=1)        # Create LC709203F driver, 1000 mAh 3.7 V LiPo

print('IC version 0x%04X, profile id 0x%04X, RSOC %d %%' % (gauge.ic_version(), gauge.profile_id(), gauge.rsoc()))

gauge.set_alarm_rsoc(20)                                               # Alarm at 20 % RSOC, (percent) → None
gauge.set_alarm_voltage(3.5)                                           # Alarm at 3.5 V, (volts) → None

# Battery monitor: one line per 5 s for a minute, host temperature fixed at 25 °C
for _ in range(12):
    gauge.set_temperature(25.0)
    print('%.3f V  %.1f %%  %.1f °C  alarm=%d' % (gauge.voltage(), gauge.percent(), gauge.temperature(), gauge.poll_interrupt()))
    time.sleep(5)

gauge.sleep()                                                          # Sleep mode, () → None
gauge.wake()                                                           # Wake up, () → None
print('sleeping:', gauge.is_sleeping())
