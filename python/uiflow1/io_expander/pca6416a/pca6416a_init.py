from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.io_expander.pca6416a import Pca6416aFull as _Pca6416aFull

_periph_pca6416a = _Pca6416aFull(_periph_i2c_conn(${_address}, bus=${_bus}))
