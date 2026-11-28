//go:build tinygo

// RRH62000 complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every RRH62000Full method once.
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

var _ = fmt.Sprint
var _ = time.Second

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x69, nil, nil) // Create I2C connection, (i2c, addr=0x69) → (*I2CConnection)

	chip, err := gas.NewRRH62000Full(conn) // Create RRH62000 driver, (connection) → (*RRH62000Full, error)
	chk(err)
	// Starts the start-up timer: I²C after 1 s, valid data after 3.3 s; no bus access yet.
	chk(chip.Init()) // Wait for start-up, check firmware version, () → error
	// Every access is at least 1 s after the previous one (tRD); the driver waits.

	major, minor, err := chip.FirmwareVersion() // Firmware version, () → (major, minor, error)
	// Two bytes from 0x75.
	fmt.Println("firmware", major, minor, err)
	a, b, c, err := chip.AlgorithmVersion() // TVOC algorithm version, () → (major, minor, patch, error)
	// Version of the ZMOD4410 algorithm in the module.
	fmt.Println("algorithm", a, b, c, err)
	uid, err := chip.UniqueID() // Unique ID, () → ([]byte (6), error)
	// Identifies the individual module.
	fmt.Printf("unique ID % X %v\n", uid, err)

	m, err := chip.Read() // Main values, () → (RRH62000Measurement, error)
	// Waits for DATA, reads the 37-byte block, checks the CRC; PM uses the KCl reference.
	fmt.Printf("%+v %v\n", m, err)
	d, err := chip.ReadAll() // Complete block, () → (RRH62000Data, error)
	// Status, 5 number-concentration bins (cm⁻³), PM KCl and smoke, T, RH, TVOC, eCO₂, IAQ.
	fmt.Printf("%+v %v\n", d, err)
	st, err := chip.ReadStatus() // Status flags, () → (RRH62000Status {Raw, HighConcentration, DustAccumulation, FanSpeed, Fan}, error)
	// Reads the current block without waiting for new data.
	fmt.Printf("%+v %v\n", st, err)
	p, err := chip.ReadParticles() // Particle values, () → (RRH62000Particles, error)
	// PM1/2.5/10 (KCl and cigarette smoke, µg/m³) and the number bins (cm⁻³).
	fmt.Printf("%+v %v\n", p, err)
	ready, err := chip.DataReady() // New sample available, () → (bool, error)
	// DATA (0x40); a sample arrives every 3 s.
	fmt.Println("data ready", ready, err)

	chk(chip.SetMovingAverage(20)) // Moving average, (count 1–60) → error
	// More averages: smoother values, slower response (24 s at 10).
	avg, err := chip.GetMovingAverage() // Moving average, () → (int, error)
	// 20.
	fmt.Println("moving average", avg, err)
	chk(chip.SetFanSpeed(80)) // Fan speed, (percent 60–100) → error
	// Lower speed is quieter; the default is 86 %.
	fan, err := chip.GetFanSpeed() // Fan speed, () → (int %, error)
	// 80.
	fmt.Println("fan", fan, err)
	chk(chip.SetCleaningInterval(3 * 24 * 3600)) // Cleaning interval, (seconds, multiple of 30) → error
	// Written as a 16-bit count of 30 s units, high byte first.
	interval, err := chip.GetCleaningInterval() // Cleaning interval, () → (int s, error)
	// 259200.
	fmt.Println("cleaning interval", interval, err)
	chk(chip.SetCleaningTime(15)) // Cleaning time, (seconds 0–60) → error
	// How long the fan runs at full speed during the automatic cleaning.
	ct, err := chip.GetCleaningTime() // Cleaning time, () → (int s, error)
	// 15.
	fmt.Println("cleaning time", ct, err)

	chk(chip.StartCleaning()) // Start fan cleaning, () → error
	// Blows dust out of the air channel.
	time.Sleep(15 * time.Second)
	done, err := chip.CleaningDone() // Cleaning status, () → (bool, error)
	// CSTATUS (0x74): true when completed.
	fmt.Println("cleaning done", done, err)
	ohms, err := chip.ReadMoxResistance() // MOX resistance, () → (uint32 Ω, error)
	// Raw resistance of the TVOC sensing element.
	fmt.Println("MOX", ohms, err)

	chk(chip.Sleep()) // Sleep, () → error
	// Stops the fan and the measurement.
	time.Sleep(5 * time.Second)
	chk(chip.Wake()) // Wake up, () → error
	// The next access waits 1 s, the next measurement 3.3 s.
	chk(chip.SoftReset()) // Reset, () → error
	// Same as power-on: restarts the start-up timer.
	m, err = chip.Read() // Main values, () → (RRH62000Measurement, error)
	// First sample after the reset.
	fmt.Printf("after reset %+v %v\n", m, err)
}
