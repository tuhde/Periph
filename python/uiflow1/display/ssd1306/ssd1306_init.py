from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.display.ssd1306 import SSD1306Full as _SSD1306Full

_periph_ssd1306 = _SSD1306Full(_periph_i2c_conn(int(${_address}), bus=int(${_bus})),
                             external_vcc=bool(int(${_external_vcc})))
