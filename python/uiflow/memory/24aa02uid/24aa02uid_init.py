from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.memory._24aa02uid import EEPROM24AA02UIDFull as _EEPROM24AA02UIDFull

_periph_24aa02uid = _EEPROM24AA02UIDFull(_periph_i2c_conn(${_address}, bus=${_bus}))
