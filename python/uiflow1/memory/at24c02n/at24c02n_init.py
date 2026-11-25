from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.memory.at24c02n import AT24C02NFull as _AT24C02NFull

_periph_at24c02n_bus = ${_bus}
_periph_at24c02n_addr = ${_address}
_periph_at24c02n = _AT24C02NFull(_periph_i2c_conn(${_address}, bus=${_bus}))
