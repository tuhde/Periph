from periph.connection.neopixel_auto import NeoPixelConnection as _periph_neopixel_conn
from periph.chips.led.sk6812rgbw import SK6812RGBWFull as _SK6812RGBWFull

_periph_sk6812rgbw = _SK6812RGBWFull(_periph_neopixel_conn(mosi=${_mosi}, sck=${_sck}, miso=${_miso}), ${_n})
