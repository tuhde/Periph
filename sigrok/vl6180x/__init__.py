"""
VL6180X proximity and ambient-light sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches address 0x29 by default (option
`address`; an address written to I2C_SLAVE__DEVICE_ADDRESS (0x212) during the
capture is followed as well). Registers are addressed with a 16-bit index
(MSB first) that auto-increments across multi-byte accesses; 16-bit registers
are big-endian. The decoder assembles the two index bytes of each write
transaction and follows auto-increment, so burst accesses are split into
named registers.

Annotates, on the data row, named register reads/writes: RESULT__RANGE_VAL in
mm, the ALS count (RESULT__ALS_VAL) with lux for the gain and integration
time seen in earlier writes, SYSRANGE__START / SYSALS__START mode,
SYSTEM__INTERRUPT_CONFIG_GPIO modes, thresholds, ALS gain and integration
time, offset, crosstalk rate, periods, scaler, interleaved mode and the
address change. The status row carries the model ID (verified against 0xB4)
and revisions, RESULT__RANGE_STATUS / RESULT__ALS_STATUS error codes by name,
RESULT__INTERRUPT_STATUS_GPIO and SYSTEM__INTERRUPT_CLEAR. Warns on a model
ID other than 0xB4 and on a single-byte register index.

No timing annotation pair is emitted: the spec's timing constraints have no
conformance-checked bus-decodable bound (see specs/tof/vl6180x.md, "Timing
Constraints", and specs/tof/vl6180x_timing.conf).
"""

from .pd import Decoder
