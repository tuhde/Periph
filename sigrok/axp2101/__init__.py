"""
AXP2101 single-cell Li-ion NVDC power-management IC sigrok protocol decoder.

Stacks on the i2c decoder and matches address 0x34. Annotates every register of
the register map by name, decodes STATUS1/STATUS2 (VBUS good, charge state,
battery direction), COMMON_CONFIG, MODULE_ENABLE, ADC_ENABLE, PWRKEY_LEVEL,
SLEEP_CTRL, the IRQ enable and status registers (as SOURCE_* names), the charger
registers (MIN_VSYS, VINDPM, IINLIM, ICC, IPRECHG, ITERM, CV as V / mA), the rail
enable registers and the DCDC / LDO voltage registers (as volts with the rail
name), assembles reads of 0x34-0x3D as 14-bit battery, TS, VBUS, VSYS and
die-temperature values and decodes BAT_PERCENT as a percentage.
"""

from .pd import Decoder
