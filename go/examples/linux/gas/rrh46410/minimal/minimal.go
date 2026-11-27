//go:build linux && !tinygo

// RRH46410 minimal example — Linux host.
//
// Constructs the driver, then prints the air quality of every sample.
package main

import (
	"fmt"
	"os"
	"strconv"
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
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := gas.NewRRH46410Minimal(conn) // Create RRH46410 driver, (connection) → (*RRH46410Minimal, error)
	chk(err)
	chk(chip.Init()) // Check product ID, start IAQ 2nd Gen mode, () → error

	for {
		r, err := chip.Read() // Wait for the next sample, () → (RRH46410Result {IAQ, TVOCMgM3, EtOHPPM, ECO2PPM, RelativeIAQ, Sample, WarmedUp}, error)
		if err != nil {
			fmt.Println("read failed:", err)
			continue
		}
		fmt.Printf("IAQ %.1f  TVOC %.2f mg/m3  EtOH %.2f ppm  eCO2 %d ppm  warmed up %v\n", r.IAQ, r.TVOCMgM3, r.EtOHPPM, r.ECO2PPM, r.WarmedUp)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
