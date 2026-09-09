//go:build linux && !tinygo

package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

func main() {
	bus, _ := strconv.Atoi(envOr("I2C_BUS", "1"))
	addr64, _ := strconv.ParseUint(envOr("I2C_ADDR", "0x76"), 0, 8)

	tr, err := connection.NewI2CConnection(bus, uint8(addr64), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer tr.Close()

	chip, err := pressure.NewBMP384Minimal(tr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	for i := 0; i < 5; i++ {
		t, err := chip.Temperature()                    // Read temperature, () → (float32 °C, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "temperature:", err)
			os.Exit(2)
		}
		p, err := chip.Pressure()                       // Read pressure, () → (float32 hPa, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "pressure:", err)
			os.Exit(2)
		}
		fmt.Printf("%.1f C, %.1f hPa\n", t, p)
		time.Sleep(time.Second)
	}
}
