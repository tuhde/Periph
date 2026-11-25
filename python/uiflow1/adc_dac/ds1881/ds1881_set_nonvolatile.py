from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ds1881 import DS1881Full as _DS1881Full

_periph_ds1881 = _DS1881Full(_periph_i2c_conn(0x28, bus=${_bus}))
_periph_ds1881.set_nonvolatile(bool(${_enabled}))
