from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina219 import INA219Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA219Full(transport)

# poll once per second for 10 seconds to characterize a power rail
print('%-10s %-10s %-10s' % ('V', 'A', 'W'))
v_min = v_max = v_sum = 0.0
i_min = i_max = i_sum = 0.0
p_min = p_max = p_sum = 0.0

for i in range(10):
    if i == 5:
        # switch on the load now to see the step in current and power
        pass

    while not ina.conversion_ready():
        pass

    v = ina.voltage()
    c = ina.current()
    p = ina.power()
    print('%-10.3f %-10.4f %-10.4f' % (v, c, p))

    if i == 0:
        v_min = v_max = v
        i_min = i_max = c
        p_min = p_max = p
    else:
        v_min = min(v_min, v)
        v_max = max(v_max, v)
        i_min = min(i_min, c)
        i_max = max(i_max, c)
        p_min = min(p_min, p)
        p_max = max(p_max, p)
    v_sum += v
    i_sum += c
    p_sum += p

    time.sleep(1)

print('V: min=%.3f max=%.3f mean=%.3f' % (v_min, v_max, v_sum / 10))
print('I: min=%.4f max=%.4f mean=%.4f' % (i_min, i_max, i_sum / 10))
print('P: min=%.4f max=%.4f mean=%.4f' % (p_min, p_max, p_sum / 10))
