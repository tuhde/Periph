from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.power.ltc4151 import LTC4151Full as _LTC4151Full

_periph_ltc4151 = _LTC4151Full(_periph_i2c_conn(${_address}, bus=${_bus}), ${_rsense} / 1000.0)
