//go:build tinygo

// CCS811 complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every method of the Full driver once.
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
	// Stores the connection only; no bus traffic yet.
	chk(err)
	chk(chip.Init()) // Check HW_ID, start application, mode 1, () → error
	// HW_ID 0x81, APP_START if the chip is in boot mode, one sample per second.

	hwID, hwVersion, err := chip.HardwareVersion() // Hardware ID and version, () → (hwID, hwVersion uint8, error)
	// HW_ID is 0x81, HW_VERSION 0x1X.
	chk(err)
	boot, app, err := chip.FirmwareVersion() // Firmware versions, () → (boot, app CCS811Version, error)
	// Bootloader and application firmware, each major.minor.trivial.
	chk(err)
	fmt.Printf("hw 0x%02X/0x%02X boot %+v app %+v\n", hwID, hwVersion, boot, app)
	st, err := chip.Status() // STATUS register, () → (uint8, error)
	// Bit 7 FW_MODE, bit 4 APP_VALID, bit 3 DATA_READY, bit 0 ERROR.
	chk(err)
	e, err := chip.Error() // ERROR_ID register, () → (uint8, error)
	// Zero unless STATUS.ERROR is set.
	chk(err)
	fmt.Printf("status 0x%02X error 0x%02X\n", st, e)

	m, err := chip.Read() // Next sample, () → (CCS811Measurement {ECO2ppm ppm, TVOCppb ppb}, error)
	// Waits for DATA_READY, then reads eCO2 and TVOC.
	chk(err)
	fmt.Printf("%+v\n", m)
	eco2, err := chip.ReadECO2() // eCO2 only, () → (uint16 ppm, error)
	// Equivalent CO2 estimated from the VOC level, 400–8192 ppm.
	chk(err)
	tvoc, err := chip.ReadTVOC() // TVOC only, () → (uint16 ppb, error)
	// Total volatile organic compounds, 0–1187 ppb.
	chk(err)
	fmt.Printf("eCO2 %d ppm TVOC %d ppb\n", eco2, tvoc)
	cur, volt, err := chip.ReadRaw() // Raw sensor data, () → (currentUA µA, voltageV V, error)
	// Sense current and the voltage across the sensor.
	chk(err)
	fmt.Printf("raw %d uA %.3f V\n", cur, volt)
	ready, err := chip.DataReady() // New sample waiting, () → (bool, error)
	// False right after a read, because reading the result clears DATA_READY.
	chk(err)
	fmt.Println("ready", ready)

	chk(chip.SetEnvironment(45.0, 22.5)) // Compensation data, (humidityRH %, temperatureC °C) → error
	// Real ambient values improve accuracy; the chip assumes 50 %RH and 25 °C otherwise.
	vRef, vNTC, err := chip.ReadNTC() // NTC circuit voltages, () → (vRefMV mV, vNTCMV mV, error)
	// Only meaningful with a reference resistor and NTC wired to the AUX pin.
	chk(err)
	r, err := chip.NTCResistance(100000) // NTC resistance, (rRefOhm Ω) → (float64 Ω, error)
	// V_NTC × R_REF / V_REF; ErrCCS811Argument without an NTC circuit (V_REF = 0).
	fmt.Printf("NTC %d mV / %d mV, %.0f ohm (%v)\n", vRef, vNTC, r, err)

	chk(chip.SetThresholds(1000, 2000, 50)) // Threshold boundaries, (lowMediumPpm, mediumHighPpm, hysteresisPpm) → error
	// eCO2 zones used by the threshold interrupt.
	baseline, err := chip.GetBaseline() // Algorithm baseline, () → (uint16, error)
	// Save it to non-volatile storage about once a day.
	chk(err)
	fmt.Printf("baseline 0x%04X\n", baseline)
	chk(chip.SetBaseline(baseline)) // Restore baseline, (baseline) → error
	// Restore a saved value only after about 20 min of warm-up.

	chk(chip.EnableInterrupt(gas.CCS811SourceDataReady)) // nINT on new data, (source) → error
	// Sets INT_DATARDY and keeps the drive mode.
	chk(chip.OnInterrupt(func(status uint8) { // Subscribe, (callback) → error
		fmt.Printf("interrupt status 0x%02X\n", status)
	}))
	// Without an INT pin on the connection the driver polls STATUS.
	time.Sleep(3 * time.Second)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	// Stops the edge handler or polling goroutine.
	p, err := chip.PollInterrupt() // STATUS without releasing nINT, () → (uint8, error)
	// DATA_READY shows whether a sample is pending.
	chk(err)
	fmt.Printf("poll 0x%02X\n", p)
	chk(chip.DisableInterrupt(gas.CCS811SourceDataReady)) // nINT off, (source) → error
	// Clears INT_DATARDY.

	chk(chip.SetMode(4)) // Drive mode, (mode 0–4) → error
	// Mode 4: raw data every 250 ms, no eCO2/TVOC updates.
	time.Sleep(500 * time.Millisecond)
	cur, volt, err = chip.ReadRaw() // Raw sensor data, () → (currentUA µA, voltageV V, error)
	// RAW_DATA keeps updating in mode 4.
	chk(err)
	fmt.Printf("raw %d uA %.3f V\n", cur, volt)
	chk(chip.SetMode(0)) // Drive mode, (mode 0–4) → error
	// Idle before switching to another mode.
	chk(chip.SetMode(1)) // Drive mode, (mode 0–4) → error
	// Back to one sample per second.

	chk(chip.SoftReset()) // Software reset, () → error
	// SW_RESET into boot mode, then APP_START and mode 1 again.
}
