from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ina260 import INA260Full
import time

connection = I2CConnection(0x40)                                       # Create I²C connection, (addr=0x40) → Connection
ina = INA260Full(connection)                                           # Create INA260 driver, (connection) → None

print('Manufacturer 0x%04X, die 0x%04X' % (ina.manufacturer_id(), ina.die_id()))

# Load power logger: sample once per second for 30 s, integrate energy in mWh
energy_mwh = 0.0
i_min = None
i_max = None
for n in range(30):
    if n == 15:
        # midway: 64x averaging with 8.244 ms conversions to show the noise reduction
        ina.configure(avg=INA260Full.AVG_64, vbus_ct=INA260Full.CT_8244US,
                      ish_ct=INA260Full.CT_8244US, mode=INA260Full.MODE_SHUNT_BUS_CONT)
        # over-current alert at 5 A, latched so a brief spike is not missed
        ina.set_alert(INA260Full.OCL, limit=5.0, latch=1)

    v = ina.voltage()
    i = ina.current()
    p = ina.power()
    energy_mwh += p * 1.0 / 3.6                                        # W * 1 s -> mWh
    i_min = i if i_min is None else min(i_min, i)
    i_max = i if i_max is None else max(i_max, i)
    flags = ina.alert_flags()                                          # clears latched AFF
    print('%6.3f V  %7.4f A  %7.3f W  %8.3f mWh  flags=0x%04X' % (v, i, p, energy_mwh, flags))
    time.sleep(1)

print('current min %.4f A, max %.4f A' % (i_min, i_max))
ina.clear_alert()
ina.shutdown()
