from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.power.max17048 import MAX17048Full as _MAX17048Full

_periph_max17048 = _MAX17048Full(_periph_i2c_conn(${_address}, bus=${_bus}), ${_cells})
