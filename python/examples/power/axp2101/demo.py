"""Demo for the AXP2101 — battery and power-rail monitor.

Prints the power-on source and the state of every rail, programs a 4.2 V /
500 mA charge profile, arms six interrupts and then prints one status line per
second (and an event line for every interrupt) for 120 s.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.power.axp2101 import AXP2101Full

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x34)
chip = AXP2101Full(connection)                                    # Create AXP2101 Full driver, (connection)

EVENTS = (
    (AXP2101Full.SOURCE_VBUS_INSERT, 'VBUS inserted'),
    (AXP2101Full.SOURCE_VBUS_REMOVE, 'VBUS removed'),
    (AXP2101Full.SOURCE_CHARGE_START, 'charging started'),
    (AXP2101Full.SOURCE_CHARGE_DONE, 'charging done'),
    (AXP2101Full.SOURCE_PWRON_SHORT, 'power key short press'),
    (AXP2101Full.SOURCE_SOC_WARN_L2, 'battery warning level 2'),
)

# --- Report why the board started and what every rail is doing ---
# eFuse defaults differ per product, so the rails are read, never assumed.
print('power-on source: 0x{:02X}'.format(chip.power_on_source()))  # Read power-on flags, () → int
for name in ('dcdc1', 'dcdc2', 'dcdc3', 'dcdc4', 'aldo1', 'aldo2', 'aldo3', 'aldo4',
             'bldo1', 'bldo2', 'cpusldo', 'dldo1', 'dldo2'):
    enabled, volts = chip.get_rail(name)                          # Read rail state, (name) → (bool, float V)
    print('{:8s} {:3s} {:.2f} V'.format(name, 'on' if enabled else 'off', volts))

# --- Program a conservative 4.2 V / 500 mA charge profile ---
# 4.2 V suits a standard Li-ion cell; 500 mA keeps heating low; the 1.5 A input
# limit lets a USB charger feed both the system and the charger.
chip.set_charge_voltage(4.2)                                      # Set charge target, (volts 4.0/4.1/4.2/4.35/4.4) → None
chip.set_charge_current(0.5)                                      # Set charge current, (amps 0–1.5) → None
chip.set_input_current_limit(1.5)                                 # Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → None
chip.set_termination(0.05)                                        # Set termination current, (amps 0–0.2, enabled=True) → None

# --- Arm low-battery warnings and the power events of interest ---
# The warning levels are 15 % and 5 %; the six sources below raise the IRQ pin.
chip.configure_battery_warning(level2=15, level1=5)               # Set warning levels, (level2=10 %, level1=1 %) → None
for mask, _ in EVENTS:
    chip.enable_interrupt(mask)                                   # Enable interrupt source, (source) → None
chip.poll_interrupt()                                             # Clear stale status, () → int

# --- Monitor for 120 s ---
# One status line per second; poll_interrupt() returns and clears every pending event.
for _ in range(120):
    d = chip.read_all()                                           # Read all measurements, () → dict
    direction = chip.status()['battery_direction']                # Read status flags, () → dict
    print('{:.3f} V {:3d} %  vbus {} {:.2f} V  sys {:.2f} V  die {:.1f} C  state {}  {}'.format(
        d['battery_voltage'], d['battery_percent'], d['vbus_present'], d['vbus_voltage'],
        d['system_voltage'], d['die_temperature'], d['charge_state'], direction))
    events = chip.poll_interrupt()                                # Read and clear events, () → int
    for mask, text in EVENTS:
        if events & mask:
            print('event: ' + text)
    time.sleep(1)
