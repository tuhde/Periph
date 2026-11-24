from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.ms5611 import MS5611Full as _MS5611Full

_periph_ms5611 = _MS5611Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_ms5611.init()