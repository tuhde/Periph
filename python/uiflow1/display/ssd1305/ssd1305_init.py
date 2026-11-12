from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.display.ssd1305 import SSD1305Full as _SSD1305Full

_periph_ssd1305 = _SSD1305Full(_periph_i2c_conn(int(${_address}), bus=int(${_bus})),
                             column_offset=int(${_offset}))
