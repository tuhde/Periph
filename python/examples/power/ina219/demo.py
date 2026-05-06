from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina219 import INA219Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA219Full(transport)

print('%-10s %-10s %-10s %-10s' % ('V_bus', 'V_shunt', 'I', 'P'))

v_list = []
i_list = []
p_list = []

for j in range(10):
    v = ina.voltage()
    vs = ina.shunt_voltage()
    i = ina.current()
    p = ina.power()

    v_list.append(v)
    i_list.append(i)
    p_list.append(p)

    print('%-10.3f %-10.5f %-10.4f %-10.4f' % (v, vs, i, p))

    if j == 3:
        print('>>> Switch on your load now <<<')

    time.sleep(1)

print('min: %.3f %.4f %.4f' % (min(v_list), min(i_list), min(p_list)))
print('max: %.3f %.4f %.4f' % (max(v_list), max(i_list), max(p_list)))
print('mean: %.3f %.4f %.4f' % (sum(v_list) / 10, sum(i_list) / 10, sum(p_list) / 10))