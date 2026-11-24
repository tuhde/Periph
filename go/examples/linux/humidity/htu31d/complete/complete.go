//go:build linux && !tinygo

// HTU31D complete example — Linux host.
//
// Calls every HTU31DFull method once.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x40) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := humidity.NewHTU31DFull(conn) // Create HTU31D driver, (connection) → *HTU31DFull
	chk(chip.Init())                     // Soft reset, () → error
	// waits 15 ms; highest resolution, heater off

	t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
	// OSR 3 / 3 by default, both CRCs verified, humidity clamped 0-100
	chk(err)
	fmt.Printf("%.2f C, %.1f %%RH\n", t, rh)
	h, err := chip.ReadHumidity() // Read humidity only, () → (float32 %RH, error)
	chk(err)
	fmt.Printf("humidity %.1f %%RH\n", h)
	tc, err := chip.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	chk(err)
	fmt.Printf("temperature %.2f C\n", tc)
	tr, rr, err := chip.ReadRaw() // Read raw codes, () → (uint16, uint16, error)
	// (t_raw, rh_raw), 16-bit
	chk(err)
	fmt.Printf("raw T %d raw RH %d\n", tr, rr)

	chk(chip.SetResolution(1, 2)) // Set oversampling, (osrRH 0-3, osrT 0-3) → error
	// higher OSR = finer resolution, longer conversion
	a, b := chip.GetResolution() // Get oversampling, () → (uint8, uint8)
	fmt.Println("resolution", a, b)
	fmt.Printf("conversion time %.4f s\n", chip.ConversionTime()) // Typical conversion time, () → float64 s
	chk(chip.SetResolution(3, 3))

	chk(chip.StartConversion()) // Start a conversion, () → error
	// non-blocking; wait ConversionTime() before reading
	time.Sleep(30 * time.Millisecond)
	t, rh, err = chip.ReadResult() // Read last conversion result, () → (float32 °C, float32 %RH, error)
	chk(err)
	fmt.Printf("result %.2f C, %.1f %%RH\n", t, rh)

	chk(chip.SetHeater(true)) // Switch the heater, (on) → error
	// 0x04; readings drift while the heater runs
	on, err := chip.IsHeaterOn() // Heater state, () → (bool, error)
	chk(err)
	fmt.Println("heater on", on)
	chk(chip.SetHeater(false))
	diag, err := chip.Diagnostic() // Read the diagnostic byte, () → (byte, error)
	// test against the HTU31DDiag* masks
	chk(err)
	fmt.Printf("diagnostic 0x%02X\n", diag)
	sn, err := chip.SerialNumber() // Read the serial number, () → (uint32, error)
	// 24-bit, CRC verified
	chk(err)
	fmt.Printf("serial 0x%06X\n", sn)
	dew, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
	// one measurement, Magnus formula
	chk(err)
	fmt.Printf("dew point %.2f C\n", dew)
	chk(chip.SoftReset()) // Soft reset, () → error
	// 0x1E, waits 15 ms; heater off, OSR 3 / 3
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
