import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.environmental.bme680 import BME680Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)

# --- Room air quality probe: 4-in-1 sensor polling with VOC event ---
# Polls all four sensors once every 5 seconds for 5 minutes (60 ticks).
# At tick 30, the user is prompted to expose the sensor to a VOC source
# (isopropyl alcohol, marker pen). Gas resistance drops sharply on exposure
# and recovers over the remaining ticks, demonstrating raw VOC sensitivity
# without the closed-source BSEC library.
bme = BME680Full(transport)                              # Create BME680 driver, (transport)
bme.configure(osrs_t=2, osrs_p=5, osrs_h=1, mode=1, filter=4)  # Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=forced, filter=15) → None
bme.set_heater(320, 150)                                 # Configure heater profile 0, (temp_c=320, duration_ms=150) → None

temps, hums, pressures, gases = [], [], [], []
for n in range(60):
    if n == 30:
        print('--- Expose sensor to VOC source now (alcohol/marker) ---')
    t, p, h, g = bme.read_all()                          # Read all sensors in one cycle, () → tuple
    temps.append(t)
    pressures.append(p)
    hums.append(h)
    if g == g:
        gases.append(g)
    print('{}: {:.1f} C, {:.1f} %RH, {:.1f} hPa, {:.0f} Ohm'.format(n, t, h, p, g))
    machine.sleep(5000)

def stats(vals):
    if not vals:
        return (0, 0, 0)
    return (min(vals), sum(vals) / len(vals), max(vals))

t_min, t_avg, t_max = stats(temps)
h_min, h_avg, h_max = stats(hums)
p_min, p_avg, p_max = stats(pressures)
g_min, g_avg, g_max = stats(gases)
print('T: {:.1f}/{:.1f}/{:.1f} C'.format(t_min, t_avg, t_max))
print('RH: {:.1f}/{:.1f}/{:.1f} %'.format(h_min, h_avg, h_max))
print('P: {:.1f}/{:.1f}/{:.1f} hPa'.format(p_min, p_avg, p_max))
print('R_gas: {:.0f}/{:.0f}/{:.0f} Ohm'.format(g_min, g_avg, g_max))
if g_min > 0:
    print('VOC response ratio: {:.1f}x'.format(g_max / g_min))
print('===DONE: 0 passed, 0 failed===')
