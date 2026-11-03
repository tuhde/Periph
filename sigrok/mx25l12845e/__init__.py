"""
MX25L12845E sigrok protocol decoder.

Decodes Macronix MX25L12845E 128-Mbit serial NOR flash SPI transactions into
chip-level operations: READ / FAST_READ / RDSFDP, PP, SE / BE32K / BE / CE,
WREN / WRDI, RDSR / WRSR, RDID / RES / REMS, DP / RDP, the secured OTP and
security register commands.
"""

from .pd import Decoder
