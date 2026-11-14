from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.humidity.si7021 import Si7021Full as _Si7021Full

_periph_si7021 = _Si7021Full(_periph_i2c_conn(${_address}, bus=${_bus}))