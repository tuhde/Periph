from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.display.uc1611s import UC1611sFull as _UC1611sFull

_periph_uc1611s = _UC1611sFull(_periph_i2c_conn(int(${_address}), bus=int(${_bus})),
                               _periph_i2c_conn(int(${_address}) + 1, bus=int(${_bus})))
