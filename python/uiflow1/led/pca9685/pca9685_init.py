from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.led.pca9685 import PCA9685Full as _PCA9685Full

_periph_pca9685 = _PCA9685Full(_periph_i2c_conn(${_address}, bus=${_bus}))
