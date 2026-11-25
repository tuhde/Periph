from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.io_expander.pca9536 import Pca9536Full as _Pca9536Full

_periph_pca9536 = _Pca9536Full(_periph_i2c_conn(${_address}, bus=${_bus}))
