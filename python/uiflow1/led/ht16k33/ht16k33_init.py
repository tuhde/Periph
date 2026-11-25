from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.led.ht16k33 import HT16K33Full as _HT16K33Full

_periph_ht16k33 = _HT16K33Full(_periph_i2c_conn(${_address}, bus=${_bus}))
