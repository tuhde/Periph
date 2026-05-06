from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina219 import INA219Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA219Full(transport)

# poll for 10 seconds, prompting user to switch load between samples 4 and 5
readings = []
for n in range(10):
    v = ina.voltage()
    i = ina.current()
    p = ina.power()
    readings.append((v, i, p))
    print('V={:.3f}  I={:.4f}  P={:.4f}'.format(v, i, p))
    if n == 3:
        print('--- switch on load now ---')
    time.sleep(1)

for label, idx in [('V', 0), ('I', 1), ('P', 2)]:
    vals = [r[idx] for r in readings]
    print('{} min={:.4f} max={:.4f} mean={:.4f}'.format(label, min(vals), max(vals), sum(vals)/len(vals)))
