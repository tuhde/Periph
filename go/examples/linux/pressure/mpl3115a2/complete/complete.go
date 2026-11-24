//go:build linux && !tinygo

// MPL3115A2 complete example — Linux host.
//
// Constructs the driver, then calls every method of the Full driver once.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := pressure.NewMPL3115A2Full(conn) // Create MPL3115A2 driver, (connection) → *MPL3115A2Full
	chk(chip.Init())                        // Reset and apply defaults, () → error
	// 0xC4 identity check, PT_DATA_CFG = 0x07, barometer, OSR 128

	id, err := chip.WhoAmI() // Read WHO_AM_I, () → (uint8, error)
	chk(err)
	fmt.Printf("who_am_i 0x%02X\n", id)
	chk(chip.SetOversample(32)) // Set the oversample ratio, (ratio 1/2/4/…/128) → error
	// 128 gives 0.25 Pa resolution at 512 ms per sample
	fmt.Println("oversample", chip.GetOversample()) // Get the oversample ratio, () → uint8
	chk(chip.SetSeaLevelPressure(101325))           // Set the sea-level reference, (pa float32) → error
	// BAR_IN in 2 Pa units
	chk(chip.SetOffsets(0, 0, 0)) // Trim the offsets, (pressurePa, temperatureC float32, altitudeM int8) → error

	p, err := chip.ReadPressure() // One-shot pressure, () → (float32 Pa, error)
	chk(err)
	t, err := chip.ReadTemperature() // One-shot temperature, () → (float32 °C, error)
	chk(err)
	p, t, err = chip.Read() // Both together, () → (float32 Pa, float32 °C, error)
	chk(err)
	fmt.Printf("%.2f Pa %.2f C\n", p, t)
	alt, err := chip.ReadAltitude() // Altimeter reading, () → (float32 m, error)
	chk(err)
	// switches to altimeter mode
	fmt.Printf("altitude %.2f m\n", alt)
	chk(chip.SetMode(false)) // Back to barometer mode, (altimeter bool) → error

	chk(chip.StartContinuous(0)) // Start periodic acquisition, (stepExp 0-15) → error
	// one sample every 2^n seconds
	active, err := chip.IsActive() // Check ACTIVE mode, () → (bool, error)
	chk(err)
	fmt.Println("active", active)
	v, tc, err := chip.ReadContinuous() // Wait for PTDR and read, () → (float32, float32 °C, error)
	chk(err)
	fmt.Printf("continuous %.2f %.2f\n", v, tc)
	ready, err := chip.DataReady() // Data-ready flag, () → (bool, error)
	chk(err)
	dv, dt, err := chip.ReadDelta() // Change since the previous sample, () → (float32, float32, error)
	chk(err)
	fmt.Println("ready", ready, dv, dt)
	chk(chip.StopContinuous()) // Back to STANDBY, () → error

	chk(chip.SetTarget(100000, 200, -5, 3)) // Threshold targets, (value, window float32, temperatureC int8, temperatureWindow uint8) → error
	chk(chip.LoadCurrentAsTarget())         // Use the current output as target, () → error
	mm, err := chip.ReadMinMax()            // Captured extremes, () → (MPL3115A2MinMax, error)
	chk(err)
	fmt.Printf("%+v\n", mm)
	chk(chip.ClearMinMax()) // Clear the extremes, () → error

	chk(chip.FIFOSetup(pressure.MPL3115A2FIFOCircular, 16)) // Configure the FIFO, (mode 0/1/2, watermark 0-63) → error
	ov, wm, n, err := chip.FIFOStatus()                     // FIFO state, () → (overflow, watermark bool, count uint8, error)
	chk(err)
	fmt.Println("fifo", ov, wm, n)
	samples, err := chip.FIFORead() // Drain the FIFO, () → ([]MPL3115A2Sample, error)
	chk(err)
	fmt.Println("fifo samples", len(samples))
	chk(chip.FIFOSetup(pressure.MPL3115A2FIFOOff, 0)) // FIFO off, (mode, watermark) → error

	chk(chip.SetInterruptPins(false, false, false, false))     // INT polarity / drive, (polarity1, openDrain1, polarity2, openDrain2 bool) → error
	chk(chip.EnableInterrupt(pressure.MPL3115A2SourceDRDY, 1)) // Route a source to INT1, (source, pin 1/2 uint8) → error
	src, err := chip.PollInterrupt()                           // Read INT_SOURCE, () → (uint8, error)
	chk(err)
	fmt.Printf("INT_SOURCE 0x%02X\n", src)
	chk(chip.OnInterrupt(func(status uint8) { fmt.Printf("INT 0x%02X\n", status) }, 1)) // Subscribe, (callback func(uint8), pin uint8) → error
	time.Sleep(time.Second)
	chk(chip.OffInterrupt())                                 // Unsubscribe, () → error
	chk(chip.DisableInterrupt(pressure.MPL3115A2SourceDRDY)) // Disable the source, (source uint8) → error
	chk(chip.Reset())                                        // Software reset and defaults, () → error
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
