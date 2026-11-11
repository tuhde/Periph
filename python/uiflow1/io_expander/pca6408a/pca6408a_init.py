from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.io_expander.pca6408a import Pca6408aFull as _Pca6408aFull

_periph_pca6408a = _Pca6408aFull(_periph_i2c_conn(${_address}, bus=${_bus}))
