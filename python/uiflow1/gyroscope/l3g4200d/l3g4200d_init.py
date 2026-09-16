from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.gyroscope.l3g4200d import L3G4200DFull as _L3G4200DFull

_periph_l3g4200d = _L3G4200DFull(_periph_i2c_conn(${_address}, bus=${_bus}))
