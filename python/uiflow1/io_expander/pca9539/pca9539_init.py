from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.io_expander.pca9539 import Pca9539Full as _Pca9539Full

_periph_pca9539 = _Pca9539Full(_periph_i2c_conn(${_address}, bus=${_bus}))
