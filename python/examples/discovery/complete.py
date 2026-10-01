from periph.discovery import scan, scan_detailed, discover

addresses = scan(1)                                    # Scan bus, (bus=1, first=0x08, last=0x77) → list[int] 7-bit addresses
                                                       # quick write per address, read byte on 0x30-0x37 / 0x50-0x5F; EBUSY counts as present
print([hex(a) for a in addresses])

detail = scan_detailed(1)                              # Scan with kernel-binding info, (bus=1, first=0x08, last=0x77) → dict {address: in_use_by_kernel}
                                                       # in_use_by_kernel is True when a kernel driver owns the address (i2cdetect "UU")
print({hex(a): busy for a, busy in detail.items()})

devices = discover(1)                                  # Discover chips, (bus=1, registry=None, active=False) → list[DiscoveredDevice]
                                                       # identity reads confirm a chip only when exactly one candidate matches
for dev in devices:
    print(hex(dev.address), dev.candidates, dev.identified, dev.driver, dev.in_use_by_kernel, dev.probe_skipped_reason, dev.aliases)

devices = discover(1, active=True)                     # Discover chips incl. write-sensitive addresses, (bus=1, registry=None, active=True) → list[DiscoveredDevice]
                                                       # also probes addresses shared with PCF8574/PCF8591/MCP4725-style chips; may change their outputs
print(len(devices))
print('===DONE: 0 passed, 0 failed===')
