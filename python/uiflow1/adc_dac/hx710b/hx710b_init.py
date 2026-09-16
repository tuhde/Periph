from periph.connection.hx711_auto import HX711Connection as _periph_hx711_conn
from periph.chips.adc_dac.hx710b import HX710BFull as _HX710BFull

_periph_hx710b = _HX710BFull(_periph_hx711_conn(${_dout}, ${_pd_sck}))
