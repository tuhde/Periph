"""
MCP3422 2-channel 18-bit delta-sigma ADC sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes addresses 0x68-0x6F (the factory-programmed
address variants). Every write byte is decoded as the configuration byte
(RDY, channel CH1/CH2, one-shot/continuous mode, resolution and data rate, PGA
gain); the general-call bytes 0x06 (reset) and 0x08 (convert) are named. A read
is shown as the 2- or 3-byte signed code (resolution from the last configuration
write in the capture, 12 bit if none) with the voltage raw * lsb / gain,
followed by the configuration byte with its RDY flag.

Warns on a write to another address, channel codes 10/11 (MCP3424 only), a read
shorter than a result frame and an unknown general-call byte.

Timing row: conversion_start (configuration write with RDY = 1 and O/C = 0, or
the 0x08 general call) and conversion_done (first read afterwards whose config
byte has RDY = 0), checked by conformance/adc_dac/mcp3422_conformance.py
against specs/adc_dac/mcp3422_timing.conf (>= 48.8 ms at 16 bit).
"""

from .pd import Decoder
