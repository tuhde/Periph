//go:build linux && !tinygo

// RRH62000 minimal example — Linux host.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x69"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x69) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := gas.NewRRH62000Minimal(conn) // Create RRH62000 driver, (connection) → (*RRH62000Minimal, error)
	chk(err)
	chk(chip.Init()) // Wait for start-up, check firmware version, () → error

	for {
		r, err := chip.Read() // Wait for a new sample, () → (RRH62000Measurement {PM1UgM3, PM2_5UgM3, PM10UgM3 µg/m³, TemperatureC °C, HumidityRH %RH, TVOC µg/m³, ECO2Ppm ppm, IAQ}, error)
		if err != nil {
			fmt.Println("read failed:", err)
			continue
		}
		fmt.Printf("PM1 %.1f  PM2.5 %.1f  PM10 %.1f ug/m3  %.2f C  %.2f %%RH  TVOC %d ug/m3  eCO2 %d ppm  IAQ %.2f\n",
			r.PM1UgM3, r.PM2_5UgM3, r.PM10UgM3, r.TemperatureC, r.HumidityRH, r.TVOC, r.ECO2Ppm, r.IAQ)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
