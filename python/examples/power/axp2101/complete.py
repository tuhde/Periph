"""Complete example for the AXP2101 — exercise every method in the public API.

Measurements, charger, battery-temperature, rail, power-key, watchdog, fuel-gauge
and interrupt control. Rail setters are called with the values just read so the
board keeps running; destructive commands are guarded by ENABLE_DANGEROUS.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.power.axp2101 import AXP2101Full

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x34)
chip = AXP2101Full(connection)                                # Create AXP2101 Full driver, (connection)
                                                              # runs the init sequence: bus check, ADC channels on, fuel gauge on
v = chip.battery_voltage()                                    # Read battery voltage, () → float V
                                                              # VBAT channel, 14-bit, 1 mV per LSB
pct = chip.battery_percent()                                  # Read state of charge, () → int %
                                                              # fuel-gauge percentage register
state = chip.charge_state()                                   # Read charge state, () → int
                                                              # STATUS2 bits 2:0: 0 trickle, 1 pre, 2 CC, 3 CV, 4 done, 5 none
vbus_ok = chip.vbus_present()                                 # Check input source, () → bool
                                                              # STATUS1.VBUS_GOOD

on_src = chip.power_on_source()                               # Power on source, () → int
                                                              # PWRON_STATUS flags: key, IRQ pin, VBUS, battery
off_src = chip.power_off_source()                             # Power off source, () → int
                                                              # PWROFF_STATUS flags: why the PMIC last powered off
vbus = chip.vbus_voltage()                                    # Vbus voltage, () → float V
                                                              # VBUS ADC channel, 1 mV per LSB
vsys = chip.system_voltage()                                  # System voltage, () → float V
                                                              # VSYS ADC channel, 1 mV per LSB
ts = chip.ts_voltage()                                        # Ts voltage, () → float V
                                                              # TS pin ADC channel, 0.5 mV per LSB
tdie = chip.die_temperature()                                 # Die temperature, () → float °C
                                                              # die-temperature channel, vendor formula (verify on hardware)
snapshot = chip.read_all()                                    # Read all, () → dict
                                                              # one call returns every measurement plus charge state and VBUS flag
flags = chip.status()                                         # Status, () → dict
                                                              # decodes STATUS1 / STATUS2 into named flags
chip.set_adc_channels(True, True, True, True, True, False)    # Set adc channels, (vbat=True, ts=True, vbus=True, vsys=True, tdie=True, gpadc=False) → None
                                                              # ADC_ENABLE channel mask; GPADC stays off
chip.set_adc_low_frequency(False)                             # Set adc low frequency, (enabled) → None
                                                              # TS_H bit 7: slow 64 s sampling only when powered off on battery
chip.set_charging(True)                                       # Set charging, (enabled) → None
                                                              # MODULE_ENABLE bit 1: main-cell charging
chip.set_charge_voltage(4.2)                                  # Set charge voltage, (volts 4.0/4.1/4.2/4.35/4.4) → None
                                                              # CV register; 4.35 / 4.4 V are for high-voltage cells only
chip.set_charge_current(0.5)                                  # Set charge current, (amps 0–1.5) → None
                                                              # ICC: 25 mA steps up to 0.2 A, 100 mA steps above; capped by the charge voltage
chip.set_precharge_current(0.125)                             # Set precharge current, (amps 0–0.2) → None
                                                              # IPRECHG in 25 mA steps
chip.set_termination(0.125, True)                             # Set termination, (amps 0–0.2, enabled=True) → None
                                                              # ITERM current in 25 mA steps plus the enable bit
chip.set_input_current_limit(1.5)                             # Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → None
                                                              # IINLIM: input current limit
chip.set_input_voltage_limit(4.36)                            # Set input voltage limit, (volts 3.88–5.08) → None
                                                              # VINDPM in 80 mV steps
chip.set_min_system_voltage(3.7)                              # Set min system voltage, (volts 3.2–3.9) → None
                                                              # MIN_VSYS in 100 mV steps
chip.set_charge_timers(60, 12, True, True, True)              # Set charge timers, (precharge_min=60, done_hours=12, precharge_enabled=True, done_enabled=True, slow_in_dpm=True) → None
                                                              # CHG_TIMER: pre-charge 40–70 min, charge-done 5–20 h
chip.set_thermal_regulation(100)                              # Set thermal regulation, (celsius 60/80/100/120) → None
                                                              # THERMAL_REG: charger thermal-regulation threshold
chip.set_die_protection(125, True)                            # Set die protection, (level1_celsius=125, detect=True) → None
                                                              # DIE_TEMP_CTRL: die over-temperature level 1 and detection
chip.set_battery_detection(True)                              # Set battery detection, (enabled) → None
                                                              # BAT_DETECT bit 0
chip.set_backup_charging(False, 2.9)                          # Set backup charging, (enabled, volts=2.9 V) → None
                                                              # MODULE_ENABLE bit 2 and BTN_BAT_VTERM for the coin cell
chip.set_chgled('type_a', 'hiz')                              # Set chgled, (mode='type_a', output='hiz') → None
                                                              # CHGLED_CTRL: charge indicator behaviour
chip.configure_ts(True, 50, 'when_adc')                       # Configure ts, (battery_sensor=True, current_ua=50 µA, source='when_adc') → None
                                                              # TS_CTRL: thermistor mode, injected current and source mode
chip.set_ts_limits(1.312, 0.176, 1.984, 0.152)                # Set ts limits, (charge_cold, charge_hot, work_cold, work_hot) → None V
                                                              # VLTF/VHTF thresholds as TS voltages (32 mV / 2 mV steps)
chip.set_ts_hysteresis(0.032, 0.004)                          # Set ts hysteresis, (low_to_normal=0.032 V, high_to_normal=0.004 V) → None
                                                              # TS_HYS_L2H (16 mV steps) and TS_HYS_H2L (4 mV steps)
chip.configure_jeita(False, None, None, True, False, 0, 1)    # Configure jeita, (enabled, cool_voltage=None, warm_voltage=None, cool_half_current=True, warm_half_current=False, cool_voltage_drop=0, warm_voltage_drop=1) → None
                                                              # JEITA profile; thresholds are left unchanged when None
enabled, volts = chip.get_rail('aldo1')                       # Get rail, (name) → (bool, float V)
                                                              # enable bit and programmed voltage of one rail
chip.set_rail_voltage('aldo1', volts)                         # Set rail voltage, (name, volts) → None V
                                                              # rounds to the nearest step inside the rail range; raises outside it
chip.set_rail_enabled('aldo1', enabled)                       # Set rail enabled, (name, enabled) → None
                                                              # enable bit in DCDC_EN / LDO_EN0 / LDO_EN1
chip.configure_dcdc('dcdc1', False, None)                     # Configure dcdc, (name, always_pwm=False, dvm=None) → None
                                                              # forced-PWM bit per DCDC; DVM only for DCDC2 / DCDC3
chip.configure_dcdc_global(False, False, False, False, 60)    # Configure dcdc global, (force_ccm=False, slow_ramp=False, frequency_spread=False, spread_100khz=False, uvp_debounce_us=60 µs) → None
                                                              # DCDC_EN bits 6 / 5 and DCDC_PWM bits 7:6, 1:0
chip.set_off_discharge(True)                                  # Set off discharge, (enabled) → None
                                                              # COMMON_CONFIG bit 5: discharge outputs when off
chip.configure_sleep(False, True, False)                      # Configure sleep, (wake_on_irq=False, pwrok_low=True, restore_voltages=False) → None
                                                              # SLEEP_CTRL bits 4, 3, 2
chip.configure_power_key(512, 6, 1.5, True, False, False)     # Configure power key, (on_ms=512, off_s=6, irq_s=1.5, long_press_off=True, restart_instead=False, force_off_16s=False) → None
                                                              # power-key timing and the actions of a long press
chip.configure_power_off(True, 2.6, True, 0x0F)               # Configure power off, (die_over_temp=True, off_voltage=2.6 V, dcdc_ovp=True, dcdc_uvp=0x0F) → None
                                                              # power-off sources: die over-temperature, VOFF threshold, DCDC OVP / UVP
chip.configure_pwrok(True, 8, True, False, False)             # Configure pwrok, (check=True, delay_ms=8 ms, off_delay=True, reverse_off_sequence=False, restart_on_low=False) → None
                                                              # PWROK_CTRL delay and sequencing
chip.set_batfet_off_enable(False)                             # Set batfet off enable, (enabled) → None
                                                              # BATFET_CTRL bit 3: BATFET on when powered off on battery only
chip.configure_fast_poweron(False, False)                     # Configure fast poweron, (poweron, wakeup, sequence=None) → None
                                                              # FAST_PWRON enables; the per-rail start sequence is left untouched
chip.configure_watchdog(False, 64, 'irq')                     # Configure watchdog, (enabled, timeout_s=64 s, action='irq') → None
                                                              # watchdog disabled here; timeout and action are still programmed
chip.feed_watchdog()                                          # Feed watchdog, () → None
                                                              # WATCHDOG_CTRL bit 3: clears the watchdog counter
chip.configure_battery_warning(10, 1)                         # Configure battery warning, (level2=10 %, level1=1 %) → None
                                                              # LOWBAT_WARN: warning levels 2 (5–20 %) and 1 (0–15 %)
chip.set_gauge_enabled(True)                                  # Set gauge enabled, (enabled) → None
                                                              # MODULE_ENABLE bit 3: fuel gauge
chip.reset_gauge(False)                                       # Reset gauge, (full=False) → None
                                                              # GAUGE_RESET bit 3 (bit 2 for the deeper reset)
chip.set_gpio1(False)                                         # Set gpio1, (low) → None
                                                              # GPIO1 open-drain output: False = Hi-Z
chip.write_data_buffer(0, 0x5A)                               # Write data buffer, (index 0–3, value 0–255) → None
                                                              # stores a byte that survives soft restarts
stored = chip.read_data_buffer(0)                             # Read data buffer, (index 0–3) → int
                                                              # reads the byte back
chip.enable_interrupt(AXP2101Full.SOURCE_VBUS_INSERT)         # Enable interrupt, (source) → None
                                                              # sets the source bits in IRQ_EN0..2
chip.disable_interrupt(AXP2101Full.SOURCE_VBUS_INSERT)        # Disable interrupt, (source) → None
                                                              # clears the source bits in IRQ_EN0..2
irq = chip.poll_interrupt()                                   # Poll interrupt, () → int
                                                              # reads IRQ_STATUS0..2, clears the bits seen and returns the 24-bit status

# Destructive commands stay disabled; set ENABLE_DANGEROUS = True only on a bench supply.
ENABLE_DANGEROUS = False
if ENABLE_DANGEROUS:
    chip.power_off()                                              # Power off, () → None
                                                                  # COMMON_CONFIG bit 0: soft power-off, cuts every output except RTCLDO
    chip.restart()                                                # Restart, () → None
                                                                  # COMMON_CONFIG bit 1: restarts the system
    chip.sleep()                                                  # Sleep, () → None
                                                                  # SLEEP_CTRL bit 0: enter sleep
    chip.wakeup()                                                 # Wakeup, () → None
                                                                  # SLEEP_CTRL bit 1: wake up
