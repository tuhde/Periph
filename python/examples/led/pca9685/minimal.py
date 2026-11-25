import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.pca9685 import PCA9685Minimal

connection = I2CConnection(0x40)
pwm = PCA9685Minimal(connection)                          # Create PCA9685 driver at 1 kHz, (connection)

for step in range(0, 11):
    pwm.set_duty(0, step / 10)                            # Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → None
    time.sleep(0.2)
pwm.set_duty(0, 0.0)
print('===DONE: 0 passed, 0 failed===')
