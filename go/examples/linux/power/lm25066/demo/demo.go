//go:build linux && !tinygo

// LM25066 demo example — Linux host.
//
// Battery monitor on a 1000 mAh 3.7 V LiPo: IDs, alarms at 20 % / 3.5 V,
// twelve readings 5 s apart, then sleep / wake.
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
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x40) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip := power.NewLM25066Full(conn, 1.0, false) // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh) → *LM25066Full

	fp := func(v float64) *float64 { return &v }                                        // Helper: pointer for optional thresholds
	fmt.Println(must(chip.ManufacturerID()), must(chip.Model()), must(chip.Revision())) // Identify the chip, () → (string, error)
	check(chip.ClearFaults())                                                           // Release SMBA after power-up, () → error

	// Hot-swap rail monitor: one line per second for a minute
	for i := 0; i < 60; i++ {
		if i == 30 {
			check(chip.SetAveraging(6))                                                            // 64-sample averaging, (exponent) → error
			check(chip.SetVinUVWarn(fp(10.0)))                                                     // VIN under-voltage warning at 10 V, (volts) → error
			check(chip.SetIinWarn(fp(20.0)))                                                       // Input over-current warning at 20 A, (amps) → error
			check(chip.EnableInterrupt(power.LM25066SourceVinUVWarn | power.LM25066SourceIinWarn)) // Unmask both alerts, (source) → error
		}
		fmt.Printf("VIN %.2f V  VOUT %.2f V  IIN %.3f A (avg %.3f)  PIN %.2f W  T %.1f C\n",
			must(chip.VoltageIn()), must(chip.VoltageOut()), must(chip.Current()),
			must(chip.CurrentAvg()), must(chip.Power()), must(chip.Temperature()))
		time.Sleep(time.Second)
	}
	fmt.Printf("peak power %.2f W\n", must(chip.PowerPeak()))    // Peak input power, () → (float64 W, error)
	fmt.Printf("diagnostic %#06x\n", must(chip.PollInterrupt())) // Alert flags, () → (uint16, error)
	check(chip.ClearFaults())                                    // Finish servicing, () → error
	// check(chip.OperationOff())   // CUTS THE LOAD RAIL - only with explicit opt-in
	// check(chip.OperationOn())
}

// must unwraps a (value, error) pair, panicking on error.
func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

// check panics on error.
func check(err error) {
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
