from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.power.lm25066 import LM25066Full as _LM25066Full

_periph_lm25066 = _LM25066Full(_periph_i2c_conn(${_address}, bus=${_bus}), ${_rsense}, bool(${_clhigh}))
