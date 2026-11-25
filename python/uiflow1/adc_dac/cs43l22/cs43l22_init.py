from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.cs43l22 import CS43L22Full as _CS43L22Full

_periph_cs43l22 = _CS43L22Full(_periph_i2c_conn(${_address}, bus=${_bus}))
