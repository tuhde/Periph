//go:build tinygo

// CCS811 demo example — TinyGo / Raspberry Pi Pico W.
//
// Indoor air-quality monitor: zones, compensation, thresholds, modes and baseline.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x5A, nil, nil) // Create I2C connection, (i2c, addr=0x5A) → (*I2CConnection)

	chip, err := gas.NewCCS811Full(conn) // Create CCS811 driver, (connection) → (*CCS811Full, error)
	chk(err)
	chk(chip.Init()) // Check HW_ID, start application, mode 1, () → error

	// --- Identity ---
	// HW_ID 0x81 identifies the CCS81x family; the firmware versions tell which
	// algorithm revision computes eCO2 and TVOC.
	hwID, hwVersion, err := chip.HardwareVersion() // Hardware ID and version, () → (uint8, uint8, error)
	chk(err)
	boot, app, err := chip.FirmwareVersion() // Firmware versions, () → (CCS811Version, CCS811Version, error)
	chk(err)
	st, _ := chip.Status() // STATUS, () → (uint8, error)
	e, _ := chip.Error()   // ERROR_ID, () → (uint8, error)
	fmt.Printf("hw 0x%02X/0x%02X boot %+v app %+v status 0x%02X error 0x%02X\n", hwID, hwVersion, boot, app, st, e)

	// --- Air-quality monitor: 30 s at 1 Hz ---
	// Mode 1 delivers a sample every second. Values settle after about 20 min of
	// warm-up; a brand-new sensor needs a 48 h burn-in.
	for i := 0; i < 30; i++ {
		m, err := chip.Read() // Next sample, () → (CCS811Measurement, error)
		chk(err)
		fmt.Printf("%2d s  eCO2 %4d ppm  TVOC %4d ppb  %s\n", i, m.ECO2ppm, m.TVOCppb, classify(m.ECO2ppm))
	}
	eco2, _ := chip.ReadECO2() // eCO2 only, () → (uint16 ppm, error)
	tvoc, _ := chip.ReadTVOC() // TVOC only, () → (uint16 ppb, error)
	fmt.Printf("eCO2 %d ppm, TVOC %d ppb\n", eco2, tvoc)

	// --- Environment compensation ---
	// Feeding the real temperature and humidity (e.g. from a BME280) corrects the
	// algorithm; without it the chip assumes 25 °C and 50 %RH.
	chk(chip.SetEnvironment(40.0, 23.0))                 // Compensation data, (humidityRH %, temperatureC °C) → error
	if r, err := chip.NTCResistance(10000); err == nil { // NTC resistance, (rRefOhm Ω) → (float64 Ω, error)
		fmt.Printf("NTC %.0f ohm\n", r)
	} else {
		vRef, vNTC, _ := chip.ReadNTC() // NTC voltages, () → (uint16 mV, uint16 mV, error)
		fmt.Printf("no NTC circuit fitted (%d mV / %d mV)\n", vRef, vNTC)
	}

	// --- Threshold interrupt: only report zone changes ---
	// With INT_THRESH the chip raises nINT only when eCO2 crosses 1000 or
	// 2000 ppm by more than the hysteresis. Polling STATUS here shows each sample.
	chk(chip.SetThresholds(1000, 2000, 50))              // Zone boundaries, (lowMediumPpm, mediumHighPpm, hysteresisPpm) → error
	chk(chip.EnableInterrupt(gas.CCS811SourceThreshold)) // Threshold interrupt, (source) → error
	for i := 0; i < 5; i++ {
		for {
			s, err := chip.PollInterrupt() // STATUS, () → (uint8, error)
			chk(err)
			if s&gas.CCS811StatusDataReady != 0 {
				break
			}
			time.Sleep(100 * time.Millisecond)
		}
		m, err := chip.Read() // Reading releases nINT, () → (CCS811Measurement, error)
		chk(err)
		fmt.Printf("event eCO2 %d ppm %s\n", m.ECO2ppm, classify(m.ECO2ppm))
	}
	chk(chip.DisableInterrupt(gas.CCS811SourceThreshold)) // Threshold interrupt off, (source) → error

	// --- Data-ready callback ---
	// The callback gets the STATUS byte; without an INT pin the driver polls.
	chk(chip.EnableInterrupt(gas.CCS811SourceDataReady)) // Data-ready interrupt, (source) → error
	chk(chip.OnInterrupt(func(status uint8) {            // Subscribe, (callback) → error
		fmt.Printf("callback status 0x%02X\n", status)
	}))
	time.Sleep(3 * time.Second)
	chk(chip.OffInterrupt())                              // Unsubscribe, () → error
	chk(chip.DisableInterrupt(gas.CCS811SourceDataReady)) // Data-ready interrupt off, (source) → error

	// --- Raw sensor data ---
	// The MOx sensor's current and voltage give its resistance; the algorithm
	// derives eCO2 and TVOC from it.
	cur, volt, err := chip.ReadRaw() // Raw data, () → (uint8 µA, float64 V, error)
	chk(err)
	rs := 0.0
	if cur > 0 {
		rs = volt / (float64(cur) * 1e-6)
	}
	fmt.Printf("raw %d uA, %.3f V, %.0f ohm\n", cur, volt, rs)

	// --- Slower sampling: mode 2 (every 10 s) ---
	// Pulse heating saves power; pass through idle when changing modes.
	chk(chip.SetMode(0)) // Idle, (mode) → error
	chk(chip.SetMode(2)) // Every 10 s, (mode) → error
	for i := 0; i < 2; i++ {
		m, err := chip.Read() // Next sample, () → (CCS811Measurement, error)
		chk(err)
		ready, _ := chip.DataReady() // Ready flag, () → (bool, error)
		fmt.Printf("mode 2 %+v ready %v\n", m, ready)
	}
	chk(chip.SetMode(0)) // Idle, (mode) → error
	chk(chip.SetMode(1)) // Back to every second, (mode) → error

	// --- Baseline ---
	// Save the baseline daily and restore it after a power cycle, but only after
	// about 20 min of warm-up; otherwise the chip relearns it from scratch.
	baseline, err := chip.GetBaseline() // Baseline, () → (uint16, error)
	chk(err)
	fmt.Printf("baseline 0x%04X\n", baseline)
	chk(chip.SetBaseline(baseline)) // Restore baseline, (baseline) → error

	// --- Wrap up ---
	// A soft reset puts the chip into boot mode; the driver starts the
	// application again in mode 1.
	chk(chip.SoftReset()) // Software reset, () → error
	st, _ = chip.Status() // STATUS, () → (uint8, error)
	fmt.Printf("status after reset 0x%02X\n", st)
}

func classify(eco2 uint16) string {
	if eco2 < 1000 {
		return "good"
	}
	if eco2 <= 1500 {
		return "fair"
	}
	return "poor"
}
