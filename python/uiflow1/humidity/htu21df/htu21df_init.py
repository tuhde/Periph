from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.humidity.htu21df import HTU21DFFull as _HTU21DFFull

_periph_htu21df = _HTU21DFFull(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_htu21df.init()