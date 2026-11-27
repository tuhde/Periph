from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.rfid.pn532 import PN532Full as _PN532Full

_periph_pn532 = _PN532Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_pn532.init()