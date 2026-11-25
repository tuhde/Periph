from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.memory.at24c64 import AT24C64Full as _AT24C64Full

_periph_at24c64_bus = ${_bus}
_periph_at24c64_addr = ${_address}
_periph_at24c64 = _AT24C64Full(_periph_i2c_conn(${_address}, bus=${_bus}, reg_bytes=2))
