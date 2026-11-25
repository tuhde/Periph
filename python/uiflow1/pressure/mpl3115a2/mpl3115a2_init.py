from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.mpl3115a2 import MPL3115A2Full as _MPL3115A2Full

_periph_mpl3115a2 = _MPL3115A2Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_mpl3115a2.init()