from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.accelerometer.fxos8700 import FXOS8700Full as _FXOS8700Full

_periph_fxos8700 = _FXOS8700Full(_periph_i2c_conn(int(${_address}), bus=${_bus}))