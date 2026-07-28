//go:build linux && !tinygo

// NEO-6 minimal example — Linux host.
//
// Constructs the driver with an I2C (DDC) connection at address 0x42,
// then loops reading NMEA sentences and printing the current GGA
// position. The DDC bus requires polling at least once per second to
// avoid the module's 2-second idle timeout dropping buffered output.
//
// To use UART instead of I2C, see the comment block below.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gnss"
	"github.com/tuhde/Periph/go/periph/connection"
)

// On UART, this example would look like:
//
//	import "github.com/tuhde/Periph/go/periph/connection"
//	import "os"
//	port, _ := connection.NewUARTConnection("/dev/ttyAMA0", 9600, nil, nil)
//	chip := gnss.NewNEO6Minimal(port, "uart")
//
// On SPI:
//
//	bus, _ := connection.NewSPIConnection("/dev/spidev0.0", 0, 10_000_000, nil, nil)
//	chip := gnss.NewNEO6Minimal(bus, "spi")

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x42"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x42) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip := gnss.NewNEO6Minimal(conn, "i2c") // Create NEO-6 driver, (connection, bus_type="i2c") → *NEO6Minimal

	for {
		_, err := chip.Update() // Read and parse one NMEA sentence, () → (bool, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "update:", err)
		}
		fix := chip.Fix() // Get last GGA fix quality, () → uint8
		sats := chip.Satellites() // Get last GGA satellite count, () → uint8
		lat := chip.Latitude() // Get last fix latitude, () → *float64
		lon := chip.Longitude() // Get last fix longitude, () → *float64
		alt := chip.Altitude() // Get last fix altitude, () → *float64
		if fix > 0 && lat != nil && lon != nil {
			fmt.Printf("fix=%d sats=%d lat=%.6f lon=%.6f alt=%.1f\n",
				fix, sats, *lat, *lon, *alt)
		} else {
			fmt.Printf("fix=%d sats=%d (waiting for fix)\n", fix, sats)
		}
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
