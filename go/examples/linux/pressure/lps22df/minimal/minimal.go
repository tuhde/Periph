//go:build linux && !tinygo

// LPS22DF minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5C"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewLPS22DFMinimal(conn, false)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 5; i++ {
		p, err := chip.Pressure()
		if err != nil {
			panic(err)
		}
		t, err := chip.Temperature()
		if err != nil {
			panic(err)
		}
		fmt.Printf("T=%.2f C  P=%.0f Pa\n", t, p)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}