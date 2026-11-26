//go:build linux && !tinygo

// CCS811 minimal example — Linux host.
//
// Constructs the driver, then prints eCO2 and TVOC for every new sample.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/gas"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5A"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x5A) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := gas.NewCCS811Minimal(conn) // Create CCS811 driver, (connection) → (*CCS811Minimal, error)
	chk(err)
	chk(chip.Init()) // Check HW_ID, start application, mode 1, () → error

	for {
		m, err := chip.Read() // Wait for the next sample, () → (CCS811Measurement {ECO2ppm ppm, TVOCppb ppb}, error)
		chk(err)
		fmt.Printf("eCO2 %d ppm, TVOC %d ppb\n", m.ECO2ppm, m.TVOCppb)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
