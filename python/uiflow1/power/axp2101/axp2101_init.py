from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.power.axp2101 import AXP2101Full as _AXP2101Full

_periph_axp2101 = _AXP2101Full(_periph_i2c_conn(${_address}, bus=${_bus}))
