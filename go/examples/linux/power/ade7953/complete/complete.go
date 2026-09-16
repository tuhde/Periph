//go:build linux && !tinygo

// ADE7953 complete example — Linux host.
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

	if v, err := chip.Version(); err == nil {
		fmt.Printf("version: 0x%02X\n", v) // Read silicon version, () → (byte, error)
	}
	if v, err := chip.Voltage(); err == nil {
		fmt.Printf("V=%.2f\n", v) // Read bus voltage, () → (float64 V, error)
	}
	if i, err := chip.Current(); err == nil {
		fmt.Printf("I_a=%.3f\n", i) // Read load current, () → (float64 A, error)
	}
	if p, err := chip.ActivePower(); err == nil {
		fmt.Printf("P_a=%.2f\n", p) // Read active power, () → (float64 W, error)
	}
	if e, err := chip.ActiveEnergy(); err == nil {
		fmt.Printf("E_a=%.4f\n", e) // Read active energy, () → (float64 Wh, error)
	}
	if pf, err := chip.PowerFactor(); err == nil {
		fmt.Printf("PF=%.3f\n", pf) // Read power factor, () → (float64 ratio, error)
	}
	if f, err := chip.LineFrequency(); err == nil {
		fmt.Printf("f=%.2f\n", f) // Read line frequency, () → (float64 Hz, error)
	}

	chip.ConfigureChannelB(30.0) // Set Channel B calibration, (current_gain_b float64) → none
	if ib, err := chip.CurrentB(); err == nil {
		fmt.Printf("I_b=%.3f\n", ib) // Read Current Channel B, () → (float64 A, error)
	}

	chip.ConfigureOvervoltage(260.0) // Configure overvoltage, (threshold float64 V) → error
	chip.ConfigureOvercurrent(40.0)  // Configure overcurrent, (threshold float64 A) → error

	chip.Reset() // Software reset, () → error
	time.Sleep(200 * time.Millisecond)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}