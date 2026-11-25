from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.memory.mb85rc import MB85RCFull as _MB85RCFull

_periph_mb85rc_bus = ${_bus}
_periph_mb85rc_addr = ${_address}
_periph_mb85rc = _MB85RCFull(_periph_i2c_conn(${_address}, bus=${_bus}, reg_bytes=2))
