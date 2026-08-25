from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.gas.ens160 import ENS160Full as _ENS160Full

_periph_ens160 = _ENS160Full(_periph_i2c_conn(${_address}, bus=${_bus}))
