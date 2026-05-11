from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina219 import INA219Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA219Full(transport)

# --- Configure for noise-sensitive power rail monitoring ---
# 128-sample averaging suppresses switching noise on a noisy 5 V rail;
# continuous mode avoids re-triggering overhead between measurements.
ina.configure(brng=1, pga=3, badc=0x0F, sadc=0x0F, mode=7)
                                                     # Configure ADC, (brng 0–1, pga 0–3, badc 0x0F, sadc 0x0F, mode 0–7) → None

# --- Sample 10 times and characterise idle vs loaded power ---
# User is prompted to connect a load at n=5 so both states appear in one run.
vs, is_, ps = [], [], []
for n in range(10):
    while not ina.conversion_ready():                # Check conversion done, () → bool
        pass
    v = ina.voltage()                                # Read bus voltage, () → float V
    i = ina.current()                                # Read load current, () → float A
    p = ina.power()                                  # Read power, () → float W
    vs.append(v)
    is_.append(i)
    ps.append(p)
    print('{:.3f} V  {:.4f} A  {:.4f} W'.format(v, i, p))

print()
print('min/max/mean')
print('V:  {:.3f} / {:.3f} / {:.3f}'.format(min(vs), max(vs), sum(vs)/len(vs)))
print('I:  {:.4f} / {:.4f} / {:.4f}'.format(min(is_), max(is_), sum(is_)/len(is_)))
print('P:  {:.4f} / {:.4f} / {:.4f}'.format(min(ps), max(ps), sum(ps)/len(ps)))
