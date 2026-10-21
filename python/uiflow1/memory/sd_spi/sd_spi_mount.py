import os as _os
_os.mount(_os.VfsFat(_BlockDeviceVFS(_periph_sd_spi)), str(${_path}))