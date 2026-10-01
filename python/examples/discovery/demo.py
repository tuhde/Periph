from periph.discovery import discover

# --- Take inventory of an unknown bench setup ---
# Scan /dev/i2c-1 and name everything that answers. A chip is only named when
# its identity register matches exactly one registry entry; shared addresses
# without an ID register stay as candidate lists.
devices = discover(1)                                  # Discover chips, (bus=1, registry=None, active=False) → list[DiscoveredDevice]

# --- Report what was found ---
# identified: confirmed chip; candidates: could be any of these; [] means the
# address answers but the registry does not know it.
for dev in devices:
    if dev.identified:
        print('0x{:02X}  {} (driver: {})'.format(dev.address, dev.identified, dev.driver))
    elif dev.in_use_by_kernel:
        print('0x{:02X}  claimed by a kernel driver, could be {}'.format(dev.address, dev.candidates))
    elif dev.candidates:
        print('0x{:02X}  one of {}'.format(dev.address, dev.candidates))
    else:
        print('0x{:02X}  unknown device'.format(dev.address))

# --- Flag addresses we deliberately did not probe ---
# Some candidates (port expanders, DACs) treat a stray write as data, so the
# identity probe is skipped unless discover(..., active=True) is used.
skipped = [d for d in devices if d.probe_skipped_reason == 'write_sensitive_candidate']
print('{} address(es) not probed because a candidate is write-sensitive'.format(len(skipped)))
print('===DONE: 0 passed, 0 failed===')
