from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.display.sh1106 import SH1106Full as _SH1106Full

_periph_sh1106 = _SH1106Full(_periph_i2c_conn(int(${_address}), bus=int(${_bus})),
                             column_offset=int(${_offset}))
