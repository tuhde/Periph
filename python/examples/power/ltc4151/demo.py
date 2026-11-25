from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ltc4151 import LTC4151Full
import time

connection = I2CConnection(0x6A)                                       # Create I²C connection, (addr=0x6A) → Connection
ltc = LTC4151Full(connection, r_sense=0.02)                            # Create LTC4151 driver, (connection, r_sense=0.02 Ω) → None

# High-side supply monitor: print the continuous scan once per second for 20 s
for n in range(20):
    d = ltc.read_all()
    print('%6.2f V  %6.2f mV  %7.4f A  %7.3f W  adin %5.3f V' % (
        d['vin'], d['sense_v'] * 1000, d['current'], d['power'], d['adin']))
    time.sleep(1)

# Compare snapshot conversion times: SENSE ~67 ms, VIN / ADIN ~33 ms
for name, ch in (('SENSE', LTC4151Full.SENSE), ('VIN', LTC4151Full.VIN), ('ADIN', LTC4151Full.ADIN)):
    t0 = time.time()
    value = ltc.snapshot(ch)
    print('%-5s snapshot %.4f V in %.0f ms' % (name, value, (time.time() - t0) * 1000))
print('snapshot mode after snapshot:', ltc.is_snapshot_mode())

# Toggle the stuck-bus timer and page read/write
ltc.set_stuck_bus_timer(False)
ltc.set_page_rw(False)
print('CONTROL (timer off, page off): 0x%02X' % ltc.read_control())
ltc.set_stuck_bus_timer(True)
ltc.set_page_rw(True)
print('CONTROL (restored): 0x%02X' % ltc.read_control())

# NTC temperature from a made-up ADIN / VIN code pair
print('temperature %.1f °C' % ltc.temperature_from_adin(900, 1200))

# 5 s min / max / average power tally
samples = []
end = time.time() + 5
while time.time() < end:
    samples.append(ltc.power())
    time.sleep(0.2)
print('power min %.3f W, max %.3f W, avg %.3f W' % (min(samples), max(samples), sum(samples) / len(samples)))
