from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.other.pca9541 import PCA9541Full as _PCA9541Full

_periph_pca9541 = _PCA9541Full(_periph_i2c_conn(${_address}, bus=${_bus}))
