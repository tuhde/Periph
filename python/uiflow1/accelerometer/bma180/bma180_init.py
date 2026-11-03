from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.accelerometer.bma180 import BMA180Full as _BMA180Full

_periph_bma180 = _BMA180Full(_periph_i2c_conn(${_address}, bus=${_bus}))