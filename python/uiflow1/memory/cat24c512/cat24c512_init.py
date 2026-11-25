from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.memory.cat24c512 import CAT24C512Full as _CAT24C512Full

_periph_cat24c512_bus = ${_bus}
_periph_cat24c512_addr = ${_address}
_periph_cat24c512 = _CAT24C512Full(_periph_i2c_conn(${_address}, bus=${_bus}, reg_bytes=2))
