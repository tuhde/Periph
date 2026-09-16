"""ADE7953 demo — sample phase voltage, current, active power and accumulated
active energy in a loop, printing a live single-phase energy-monitor feed."""

from periph.connection.i2c_micropython import I2CConnection
from periph.chips.power.ade7953 import ADE7953Full
import time


I2C_ADDR = 0x38
VOLTAGE_GAIN = 251.0
CURRENT_GAIN = 30.0


def main():
    connection = I2CConnection(I2C_ADDR)
    ade = ADE7953Full(connection, VOLTAGE_GAIN, CURRENT_GAIN)      # Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    # --- Prepare the chip: enable overcurrent interrupt and pin it to IRQ ---
    # The ADE7953 exposes power-quality events via the IRQ pin. Driving
    # OIA through the chip's own alert output lets the host react without
    # polling every reading every cycle.
    ade.configure_overcurrent(40.0)                                 # Configure overcurrent, (threshold) → None
    ade.enable_interrupt(ADE7953Source.OIA)                         # Enable interrupt source, (source) → None

    # --- Sample at 1 Hz and emit one structured line per cycle ---
    # The energy accumulator resets on read by default (RSTREAD = 1), so
    # active_energy() returns watt-hours accumulated since the previous
    # call. Callers wanting a running total accumulate the returned deltas
    # themselves (or call set_read_with_reset(False) and track the
    # 24-bit register's own rollovers).
    print('%-10s %-10s %-10s %-12s' % ('V', 'A', 'W', 'Wh/s'))
    total_wh = 0.0
    while True:
        v = ade.voltage()                                           # Read bus voltage, () → float V
        i = ade.current()                                           # Read load current, () → float A
        p = ade.active_power()                                      # Read active power, () → float W
        e = ade.active_energy()                                     # Read active energy, () → float Wh
        total_wh += e
        print('%-10.2f %-10.3f %-10.2f %-12.5f' % (v, i, p, e))

        # --- Poll the interrupt status to detect latched OIA events ---
        status = ade.interrupt_status('a')                          # Read interrupt status A, (group='a') → int
        if status & ADE7953Source.OIA:
            print('!! IRQ: Current Channel A exceeded the overcurrent threshold')
            ade.clear_interrupts('a')                               # Read-and-clear interrupts A, (group='a') → int
        time.sleep(1)


main()