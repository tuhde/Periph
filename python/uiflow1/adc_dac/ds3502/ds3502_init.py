from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ds3502 import DS3502Full as _DS3502Full

_periph_ds3502 = _DS3502Full(_periph_i2c_conn(${_address}, bus=${_bus}), ${_v_high}, ${_v_low})
