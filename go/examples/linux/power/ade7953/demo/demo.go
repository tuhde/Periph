//go:build linux && !tinygo

// ADE7953 demo — Linux host: sample phase voltage, current, active power and
// accumulated active energy in a loop, printing a live single-phase
// energy-monitor feed.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, _ := strconv.Atoi(envOr("I2C_BUS", "1"))
	addr64, _ := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)

	conn, err := connection.NewI2CConnection(bus, uint8(addr64), nil, nil) // Create I2C connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := power.NewADE7953Full(conn, 251.0, 30.0) // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V) → (*ADE7953Full, error)
	if err != nil {
		panic(err)
	}

	// --- Prepare the chip: configure overcurrent threshold and let the
	//     chip's own IRQ pin alert on overcurrent ---
	chip.ConfigureOvercurrent(40.0) // Configure overcurrent, (threshold float64 A) → error

	// --- Sample at 1 Hz and emit one structured line per cycle ---
	// The energy accumulator resets on read by default (RSTREAD = 1), so
	// activeEnergy() returns watt-hours accumulated since the previous
	// call. Callers wanting a running total accumulate the returned deltas
	// themselves.
	fmt.Printf("%-10s %-10s %-10s %-12s\n", "V", "A", "W", "Wh/s")
	for {
		v, _ := chip.Voltage()    // Read bus voltage, () → (float64 V, error)
		i, _ := chip.Current()    // Read load current, () → (float64 A, error)
		p, _ := chip.ActivePower() // Read active power, () → (float64 W, error)
		e, _ := chip.ActiveEnergy() // Read active energy, () → (float64 Wh, error)
		fmt.Printf("%-10.2f %-10.3f %-10.2f %-12.5f\n", v, i, p, e)
		time.Sleep(1 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}