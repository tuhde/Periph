from periph.connection.hx711_auto import HX711Connection as _periph_hx711_conn
from periph.chips.adc_dac.hx710a import HX710AFull as _HX710AFull

_periph_hx710a = _HX710AFull(_periph_hx711_conn(${_dout}, ${_pd_sck}))
