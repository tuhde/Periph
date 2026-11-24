from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.light.bh1750 import BH1750Full as _BH1750Full

_periph_bh1750 = _BH1750Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_bh1750.init()