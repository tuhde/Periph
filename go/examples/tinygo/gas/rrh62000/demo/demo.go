//go:build tinygo

// RRH62000 demo example — TinyGo / Raspberry Pi Pico W.
//
// Room air-quality station: settings, 30 s of samples with an IAQ rating,
// particle bins, status, fan cleaning, sleep and reset.
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
	chk(chip.Init()) // Wait for start-up, check firmware version, () → error

	// --- Identity and settings ---
	// The module may only be accessed once per second; every call below waits
	// for that interval, so this section takes several seconds.
	if major, minor, err := chip.FirmwareVersion(); err == nil { // Firmware version, () → (major, minor, error)
		fmt.Printf("firmware %d.%d\n", major, minor)
	}
	if a, b, c, err := chip.AlgorithmVersion(); err == nil { // Algorithm version, () → (major, minor, patch, error)
		fmt.Printf("algorithm %d.%d.%d\n", a, b, c)
	}
	if uid, err := chip.UniqueID(); err == nil { // Unique ID, () → ([]byte, error)
		fmt.Printf("unique ID %X\n", uid)
	}
	avg, _ := chip.GetMovingAverage()         // Moving average, () → (int, error)
	fan, _ := chip.GetFanSpeed()              // Fan speed, () → (int %, error)
	interval, _ := chip.GetCleaningInterval() // Cleaning interval, () → (int s, error)
	ct, _ := chip.GetCleaningTime()           // Cleaning time, () → (int s, error)
	fmt.Printf("moving average %d, fan %d %%, cleaning every %d s for %d s\n", avg, fan, interval, ct)

	// --- Room air-quality station ---
	// A complete sample arrives every 3 s. The IAQ index follows the German UBA
	// scale; PM2.5 uses the KCl reference. Allow up to 48 h of conditioning on
	// first use before trusting TVOC and eCO2.
	end := time.Now().Add(30 * time.Second)
	for time.Now().Before(end) {
		d, err := chip.ReadAll() // Complete block, () → (RRH62000Data, error)
		if err != nil {
			fmt.Println("read failed:", err)
			continue
		}
		fmt.Printf("IAQ %.2f (%s)  PM2.5 %.1f ug/m3  eCO2 %d ppm  TVOC %d ug/m3  %.1f C  %.1f %%RH\n",
			d.IAQ, iaqLabel(d.IAQ), d.Particles.PM2_5UgM3, d.ECO2Ppm, d.TVOC, d.TemperatureC, d.HumidityRH)
	}

	// --- Particle counts and status ---
	// Number concentrations count particles from the bin size up to 10 µm per cm³.
	if p, err := chip.ReadParticles(); err == nil { // Particle values, () → (RRH62000Particles, error)
		fmt.Printf("particles/cm3  >0.3um %.1f  >0.5um %.1f  >1um %.1f  >2.5um %.1f  >4um %.1f\n",
			p.NC0_3, p.NC0_5, p.NC1, p.NC2_5, p.NC4)
		fmt.Printf("PM2.5 KCl %.1f / smoke %.1f ug/m3\n", p.PM2_5UgM3, p.PM2_5SmokeUgM3)
	}
	st, err := chip.ReadStatus() // Status flags, () → (RRH62000Status, error)
	fmt.Printf("status %+v %v\n", st, err)
	ready, _ := chip.DataReady() // New sample available, () → (bool, error)
	fmt.Println("new data pending:", ready)

	// --- Quieter, smoother operation ---
	// 30 moving averages smooth the PM values (slower response); 70 % fan speed
	// is quieter.
	chk(chip.SetMovingAverage(30)) // Moving average, (count 1–60) → error
	chk(chip.SetFanSpeed(70))      // Fan speed, (percent 60–100) → error
	avg, _ = chip.GetMovingAverage()
	fan, _ = chip.GetFanSpeed()
	fmt.Printf("moving average %d, fan %d %%\n", avg, fan)

	// --- Fan cleaning ---
	// A manual cleaning blows dust out of the air channel; the automatic
	// cleaning is shortened to every 3 days for 10 s.
	chk(chip.StartCleaning()) // Start fan cleaning, () → error
	for i := 0; i < 30; i++ {
		if done, _ := chip.CleaningDone(); done { // Cleaning status, () → (bool, error)
			fmt.Println("cleaning done")
			break
		}
	}
	chk(chip.SetCleaningInterval(3 * 24 * 3600)) // Cleaning interval, (seconds) → error
	chk(chip.SetCleaningTime(10))                // Cleaning time, (seconds) → error
	interval, _ = chip.GetCleaningInterval()
	fmt.Printf("cleaning every %d s\n", interval)

	// --- Sleep, diagnostics, reset ---
	// Sleep stops the fan; after wake-up the first valid sample takes 3.3 s.
	chk(chip.Sleep()) // Sleep, () → error
	time.Sleep(5 * time.Second)
	chk(chip.Wake())                    // Wake up, () → error
	ohms, _ := chip.ReadMoxResistance() // MOX resistance, () → (uint32 Ω, error)
	fmt.Printf("MOX resistance %d ohm\n", ohms)
	chk(chip.SoftReset()) // Reset, () → error
	m, err := chip.Read() // Main values, () → (RRH62000Measurement, error)
	fmt.Printf("after reset %+v %v\n", m, err)
}

func iaqLabel(iaq float64) string {
	switch {
	case iaq < 2:
		return "very good"
	case iaq < 3:
		return "good"
	case iaq < 4:
		return "medium"
	case iaq < 5:
		return "poor"
	}
	return "bad"
}
