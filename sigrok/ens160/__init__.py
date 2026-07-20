##
## This file is part of the libsigrokdecode project.
##
## Copyright (C) 2024 Periph Project
##
## This program is free software; you can redistribute it and/or modify
## it under the terms of the GNU General Public License as published by
## the Free Software Foundation; either version 2 of the License, or
## (at your option) any later version.
##
## This program is distributed in the hope that it will be useful,
## but WITHOUT ANY WARRANTY; without even the implied warranty of
## MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
## GNU General Public License for more details.
##
## You should have received a copy of the GNU General Public License
## along with this program; if not, see <http://www.gnu.org/licenses/>.
##

'''
ENS160 digital multi-gas sensor decoder.

This decoder interprets I2C/SPI transactions with the ScioSense ENS160
digital multi-gas sensor, which measures TVOC, eCO2, and AQI.

Supported features:
- Register read/write decoding
- OPMODE, CONFIG, COMMAND register interpretation
- DEVICE_STATUS validity flag decoding
- DATA_AQI, DATA_TVOC, DATA_ECO2 value interpretation
- TEMP_IN, RH_IN compensation value decoding
- PART_ID verification

I2C addresses: 0x52 (ADDR low), 0x53 (ADDR high)
'''

from .pd import Decoder
