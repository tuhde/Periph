import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.environmental.bme280 import BME280Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)

# --- Weather monitoring preset: forced mode, ×1/×1/×1, filter off ---
# BME280 datasheet "weather monitoring" preset: minimum power, single-shot,
# 8 ms typ / 9.3 ms max per cycle. Sleep between samples to demonstrate
# battery-friendly indoor monitoring.
bme = BME280Full(transport)                            # Create BME280 driver, (transport, bus_type='i2c')
bme.configure(osrs_t=1, osrs_p=1, osrs_h=1, mode=1, filter=0, t_sb=0)  # Configure chip, (osrs_t=×1, osrs_p=×1, osrs_h=×1, mode=forced, filter=off, t_sb=0) → None

temps, hums, pressures, alts, dews = [], [], [], [], []
for n in range(10):
    t = bme.temperature()                              # Read temperature, () → float °C
    p = bme.pressure()                                 # Read pressure, () → float hPa
    h = bme.humidity()                                 # Read humidity, () → float %RH
    a = bme.altitude()                                 # Compute altitude, (sea_level_hpa=1013.25) → float m
    d = bme.dew_point()                                # Compute dew point, () → float °C
    temps.append(t)
    hums.append(h)
    pressures.append(p)
    alts.append(a)
    dews.append(d)
    print('{}: {:.1f} C, {:.1f} %RH, {:.1f} hPa, dew={:.1f} C, alt={:.1f} m'.format(n, t, h, p, d, a))
    machine.sleep(1000)

# --- Half-way: breathe gently on the sensor for 3 seconds ---
# User exposes the sensor to humid exhaled air; humidity climbs from ~40 %RH
# toward ~80 %RH, dew point spikes toward ambient temperature, pressure
# stays flat, temperature rises only slightly. Demonstrates the humidity
# channel's response and the dew-point alarm use case.
if len(temps) >= 2:
    n = len(temps) // 2
    print('--- Breathe gently on the sensor for 3 seconds ---')
    machine.sleep_ms(3000)
    t = bme.temperature()                              # Read temperature, () → float °C
    p = bme.pressure()                                 # Read pressure, () → float hPa
    h = bme.humidity()                                 # Read humidity, () → float %RH
    a = bme.altitude()                                 # Compute altitude, (sea_level_hpa=1013.25) → float m
    d = bme.dew_point()                                # Compute dew point, () → float °C
    temps.append(t)
    hums.append(h)
    pressures.append(p)
    alts.append(a)
    dews.append(d)
    print('after breath: {:.1f} C, {:.1f} %RH, {:.1f} hPa, dew={:.1f} C, alt={:.1f} m'.format(t, h, p, d, a))

def stats(vals):
    if not vals:
        return (0, 0, 0)
    return (min(vals), sum(vals) / len(vals), max(vals))

t_min, t_avg, t_max = stats(temps)
h_min, h_avg, h_max = stats(hums)
p_min, p_avg, p_max = stats(pressures)
a_min, a_avg, a_max = stats(alts)
d_min, d_avg, d_max = stats(dews)
print('T:    {:.1f}/{:.1f}/{:.1f} C'.format(t_min, t_avg, t_max))
print('RH:   {:.1f}/{:.1f}/{:.1f} %'.format(h_min, h_avg, h_max))
print('P:    {:.1f}/{:.1f}/{:.1f} hPa'.format(p_min, p_avg, p_max))
print('alt:  {:.1f}/{:.1f}/{:.1f} m'.format(a_min, a_avg, a_max))
print('dew:  {:.1f}/{:.1f}/{:.1f} C'.format(d_min, d_avg, d_max))
print('===DONE: 0 passed, 0 failed===')
