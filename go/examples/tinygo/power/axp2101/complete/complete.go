//go:build tinygo

// AXP2101 complete example — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func check(err error) {
	if err != nil {
		panic(err)
	}
}

var _ = time.Second

func main() {
	i2c := machine.I2C1
	check(i2c.Configure(machine.I2CConfig{SDA: machine.GP4, SCL: machine.GP5, Frequency: 400_000}))
	conn := connection.NewI2CConnection(i2c, 0x34, nil, nil)
	chip, err := power.NewAXP2101Full(conn) // Create AXP2101 Full driver, (conn) → (*AXP2101Full, error)
	check(err)                              // runs the init sequence: bus check, ADC channels on, fuel gauge on
	onSrc, err := chip.PowerOnSource()      // Power on source, () → (uint8, error)
	// PWRON_STATUS flags: key, IRQ pin, VBUS, battery
	check(err)
	fmt.Println("power on source:", onSrc)
	offSrc, err := chip.PowerOffSource() // Power off source, () → (uint8, error)
	// PWROFF_STATUS flags: why the PMIC last powered off
	check(err)
	fmt.Println("power off source:", offSrc)
	vbus, err := chip.VbusVoltage() // Vbus voltage, () → (float64 V, error)
	// VBUS ADC channel, 1 mV per LSB
	check(err)
	fmt.Println("vbus voltage:", vbus)
	vsys, err := chip.SystemVoltage() // System voltage, () → (float64 V, error)
	// VSYS ADC channel, 1 mV per LSB
	check(err)
	fmt.Println("system voltage:", vsys)
	ts, err := chip.TSVoltage() // Ts voltage, () → (float64 V, error)
	// TS pin ADC channel, 0.5 mV per LSB
	check(err)
	fmt.Println("ts voltage:", ts)
	tdie, err := chip.DieTemperature() // Die temperature, () → (float64 °C, error)
	// die-temperature channel, vendor formula (verify on hardware)
	check(err)
	fmt.Println("die temperature:", tdie)
	snapshot, err := chip.ReadAll() // Read all, () → (struct, error)
	// one call returns every measurement plus charge state and VBUS flag
	check(err)
	fmt.Println("read all:", snapshot)
	flags, err := chip.Status() // Status, () → (struct, error)
	// decodes STATUS1 / STATUS2 into named flags
	check(err)
	fmt.Println("status:", flags)
	err = chip.SetADCChannels(true, true, true, true, true, false) // Set adc channels, (vbat=True, ts=True, vbus=True, vsys=True, tdie=True, gpadc=False) → error
	// ADC_ENABLE channel mask; GPADC stays off
	check(err)
	err = chip.SetADCLowFrequency(false) // Set adc low frequency, (enabled) → error
	// TS_H bit 7: slow 64 s sampling only when powered off on battery
	check(err)
	err = chip.SetCharging(true) // Set charging, (enabled) → error
	// MODULE_ENABLE bit 1: main-cell charging
	check(err)
	err = chip.SetChargeVoltage(4.2) // Set charge voltage, (volts 4.0/4.1/4.2/4.35/4.4) → error
	// CV register; 4.35 / 4.4 V are for high-voltage cells only
	check(err)
	err = chip.SetChargeCurrent(0.5) // Set charge current, (amps 0–1.5) → error
	// ICC: 25 mA steps up to 0.2 A, 100 mA steps above; capped by the charge voltage
	check(err)
	err = chip.SetPrechargeCurrent(0.125) // Set precharge current, (amps 0–0.2) → error
	// IPRECHG in 25 mA steps
	check(err)
	err = chip.SetTermination(0.125, true) // Set termination, (amps 0–0.2, enabled=True) → error
	// ITERM current in 25 mA steps plus the enable bit
	check(err)
	err = chip.SetInputCurrentLimit(1.5) // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → error
	// IINLIM: input current limit
	check(err)
	err = chip.SetInputVoltageLimit(4.36) // Set input voltage limit, (volts 3.88–5.08) → error
	// VINDPM in 80 mV steps
	check(err)
	err = chip.SetMinSystemVoltage(3.7) // Set min system voltage, (volts 3.2–3.9) → error
	// MIN_VSYS in 100 mV steps
	check(err)
	err = chip.SetChargeTimers(60, 12, true, true, true) // Set charge timers, (precharge_min=60, done_hours=12, precharge_enabled=True, done_enabled=True, slow_in_dpm=True) → error
	// CHG_TIMER: pre-charge 40–70 min, charge-done 5–20 h
	check(err)
	err = chip.SetThermalRegulation(100) // Set thermal regulation, (celsius 60/80/100/120) → error
	// THERMAL_REG: charger thermal-regulation threshold
	check(err)
	err = chip.SetDieProtection(125, true) // Set die protection, (level1_celsius=125, detect=True) → error
	// DIE_TEMP_CTRL: die over-temperature level 1 and detection
	check(err)
	err = chip.SetBatteryDetection(true) // Set battery detection, (enabled) → error
	// BAT_DETECT bit 0
	check(err)
	err = chip.SetBackupCharging(false, 2.9) // Set backup charging, (enabled, volts=2.9 V) → error
	// MODULE_ENABLE bit 2 and BTN_BAT_VTERM for the coin cell
	check(err)
	err = chip.SetChgled("type_a", "hiz") // Set chgled, (mode='type_a', output='hiz') → error
	// CHGLED_CTRL: charge indicator behaviour
	check(err)
	err = chip.ConfigureTS(true, 50, "when_adc") // Configure ts, (battery_sensor=True, current_ua=50 µA, source='when_adc') → error
	// TS_CTRL: thermistor mode, injected current and source mode
	check(err)
	err = chip.SetTSLimits(1.312, 0.176, 1.984, 0.152) // Set ts limits, (charge_cold, charge_hot, work_cold, work_hot) → error V
	// VLTF/VHTF thresholds as TS voltages (32 mV / 2 mV steps)
	check(err)
	err = chip.SetTSHysteresis(0.032, 0.004) // Set ts hysteresis, (low_to_normal=0.032 V, high_to_normal=0.004 V) → error
	// TS_HYS_L2H (16 mV steps) and TS_HYS_H2L (4 mV steps)
	check(err)
	err = chip.ConfigureJEITA(false, -1, -1, true, false, 0, 1) // Configure jeita, (enabled, cool_voltage=None, warm_voltage=None, cool_half_current=True, warm_half_current=False, cool_voltage_drop=0, warm_voltage_drop=1) → error
	// JEITA profile; thresholds are left unchanged when None
	check(err)
	enabled, volts, err := chip.GetRail("aldo1") // Get rail, (name) → (bool, float64 V, error)
	// enable bit and programmed voltage of one rail
	check(err)
	fmt.Println("rail aldo1:", enabled, volts)
	err = chip.SetRailVoltage("aldo1", volts) // Set rail voltage, (name, volts) → error V
	// rounds to the nearest step inside the rail range; raises outside it
	check(err)
	err = chip.SetRailEnabled("aldo1", enabled) // Set rail enabled, (name, enabled) → error
	// enable bit in DCDC_EN / LDO_EN0 / LDO_EN1
	check(err)
	err = chip.ConfigureDCDC("dcdc1", false, -1) // Configure dcdc, (name, always_pwm=False, dvm=None) → error
	// forced-PWM bit per DCDC; DVM only for DCDC2 / DCDC3
	check(err)
	err = chip.ConfigureDCDCGlobal(false, false, false, false, 60) // Configure dcdc global, (force_ccm=False, slow_ramp=False, frequency_spread=False, spread_100khz=False, uvp_debounce_us=60 µs) → error
	// DCDC_EN bits 6 / 5 and DCDC_PWM bits 7:6, 1:0
	check(err)
	err = chip.SetOffDischarge(true) // Set off discharge, (enabled) → error
	// COMMON_CONFIG bit 5: discharge outputs when off
	check(err)
	err = chip.ConfigureSleep(false, true, false) // Configure sleep, (wake_on_irq=False, pwrok_low=True, restore_voltages=False) → error
	// SLEEP_CTRL bits 4, 3, 2
	check(err)
	err = chip.ConfigurePowerKey(512, 6, 1.5, true, false, false) // Configure power key, (on_ms=512, off_s=6, irq_s=1.5, long_press_off=True, restart_instead=False, force_off_16s=False) → error
	// power-key timing and the actions of a long press
	check(err)
	err = chip.ConfigurePowerOff(true, 2.6, true, 0x0F) // Configure power off, (die_over_temp=True, off_voltage=2.6 V, dcdc_ovp=True, dcdc_uvp=0x0F) → error
	// power-off sources: die over-temperature, VOFF threshold, DCDC OVP / UVP
	check(err)
	err = chip.ConfigurePwrok(true, 8, true, false, false) // Configure pwrok, (check=True, delay_ms=8 ms, off_delay=True, reverse_off_sequence=False, restart_on_low=False) → error
	// PWROK_CTRL delay and sequencing
	check(err)
	err = chip.SetBatfetOffEnable(false) // Set batfet off enable, (enabled) → error
	// BATFET_CTRL bit 3: BATFET on when powered off on battery only
	check(err)
	err = chip.ConfigureFastPoweron(false, false, nil) // Configure fast poweron, (poweron, wakeup, sequence=None) → error
	// FAST_PWRON enables; the per-rail start sequence is left untouched
	check(err)
	err = chip.ConfigureWatchdog(false, 64, "irq") // Configure watchdog, (enabled, timeout_s=64 s, action='irq') → error
	// watchdog disabled here; timeout and action are still programmed
	check(err)
	err = chip.FeedWatchdog() // Feed watchdog, () → error
	// WATCHDOG_CTRL bit 3: clears the watchdog counter
	check(err)
	err = chip.ConfigureBatteryWarning(10, 1) // Configure battery warning, (level2=10 %, level1=1 %) → error
	// LOWBAT_WARN: warning levels 2 (5–20 %) and 1 (0–15 %)
	check(err)
	err = chip.SetGaugeEnabled(true) // Set gauge enabled, (enabled) → error
	// MODULE_ENABLE bit 3: fuel gauge
	check(err)
	err = chip.ResetGauge(false) // Reset gauge, (full=False) → error
	// GAUGE_RESET bit 3 (bit 2 for the deeper reset)
	check(err)
	err = chip.SetGPIO1(false) // Set gpio1, (low) → error
	// GPIO1 open-drain output: False = Hi-Z
	check(err)
	err = chip.WriteDataBuffer(0, 0x5A) // Write data buffer, (index 0–3, value 0–255) → error
	// stores a byte that survives soft restarts
	check(err)
	stored, err := chip.ReadDataBuffer(0) // Read data buffer, (index 0–3) → (uint8, error)
	// reads the byte back
	check(err)
	fmt.Println("read data buffer:", stored)
	err = chip.EnableInterrupt(power.AXP2101SourceVbusInsert) // Enable interrupt, (source) → error
	// sets the source bits in IRQ_EN0..2
	check(err)
	err = chip.DisableInterrupt(power.AXP2101SourceVbusInsert) // Disable interrupt, (source) → error
	// clears the source bits in IRQ_EN0..2
	check(err)
	irq, err := chip.PollInterrupt() // Poll interrupt, () → (uint8, error)
	// reads IRQ_STATUS0..2, clears the bits seen and returns the 24-bit status
	check(err)
	fmt.Println("poll interrupt:", irq)

	// Destructive commands stay disabled; set enableDangerous only on a bench supply.
	const enableDangerous = false
	if enableDangerous {
		err = chip.PowerOff() // Power off, () → error
		// COMMON_CONFIG bit 0: soft power-off, cuts every output except RTCLDO
		check(err)
		err = chip.Restart() // Restart, () → error
		// COMMON_CONFIG bit 1: restarts the system
		check(err)
		err = chip.Sleep() // Sleep, () → error
		// SLEEP_CTRL bit 0: enter sleep
		check(err)
		err = chip.Wakeup() // Wakeup, () → error
		// SLEEP_CTRL bit 1: wake up
		check(err)
	}
}
