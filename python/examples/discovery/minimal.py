from periph.discovery import discover

for dev in discover(1):                                # Discover chips, (bus=1, active=False) → list[DiscoveredDevice]
    print(hex(dev.address), dev.identified or dev.candidates)
print('===DONE: 0 passed, 0 failed===')
