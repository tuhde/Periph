from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.pcf8591 import PCF8591Full as _PCF8591Full

_periph_pcf8591 = _PCF8591Full(_periph_i2c_conn(${_address}, bus=${_bus}))
