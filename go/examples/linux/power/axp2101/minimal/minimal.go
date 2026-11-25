//go:build linux && !tinygo

// AXP2101 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func check(err error) {
	if err != nil {
		panic(err)
	}
}

var _ = time.Second

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	check(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x34"), 0, 8)
	check(err)
	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	check(err)
	defer conn.Close()
	chip, err := power.NewAXP2101Minimal(conn) // Create AXP2101 driver, (conn) → (*AXP2101Minimal, error)
	check(err)
	for i := 0; i < 10; i++ {
		v, err := chip.BatteryVoltage() // Read battery voltage, () → (float64 V, error)
		check(err)
		pct, err := chip.BatteryPercent() // Read state of charge, () → (int %, error)
		check(err)
		state, err := chip.ChargeState() // Read charge state, () → (int, error)
		check(err)
		vbus, err := chip.VbusPresent() // Check input source, () → (bool, error)
		check(err)
		fmt.Printf("%.3f V  %d %%  state=%d  vbus=%v\n", v, pct, state, vbus)
		time.Sleep(time.Second)
	}
}
