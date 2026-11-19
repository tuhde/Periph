from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.mpl115a2 import MPL115A2Full as _MPL115A2Full

_periph_mpl115a2 = _MPL115A2Full(_periph_i2c_conn(${_address}, bus=${_bus}))
