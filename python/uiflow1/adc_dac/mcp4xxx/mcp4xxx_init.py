from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.connection.output_pin import MicroPythonOutputPin as _periph_out_pin
from periph.chips.adc_dac.mcp4xxx import Mcp4xxxFull as _Mcp4xxxFull

_periph_mcp4xxx = _Mcp4xxxFull(_periph_i2c_conn(${_address}, bus=${_bus}), part='MCP%d' % ${_part}, r_ab=${_r_ab}, hvc=(_periph_out_pin(${_hvc}) if ${_hvc} >= 0 else None))
