from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.rfid.st25dv import ST25DVFull as _ST25DVFull

_periph_st25dv = _ST25DVFull(_periph_i2c_conn(0x53, bus=${_bus}, reg_bytes=2), _periph_i2c_conn(0x57, bus=${_bus}, reg_bytes=2))
