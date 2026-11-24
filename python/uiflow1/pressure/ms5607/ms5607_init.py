from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.ms5607 import MS5607Full as _MS5607Full

_periph_ms5607 = _MS5607Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_ms5607.init()