//go:build linux && !tinygo

// LM25066 complete example — Linux host.
//
// Calls every method of the Full driver once.
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

	fp := func(v float64) *float64 { return &v }                                                                      // Helper: pointer for optional thresholds (nil disables)
	fmt.Println(must(chip.VoltageIn()))                                                                               // Input voltage, () → (float64 V, error)
	fmt.Println(must(chip.VoltageOut()))                                                                              // Output voltage, () → (float64 V, error)
	fmt.Println(must(chip.Current()))                                                                                 // Input current, () → (float64 A, error)
	fmt.Println(must(chip.Power()))                                                                                   // Input power, () → (float64 W, error)
	fmt.Println(must(chip.Temperature()))                                                                             // MOSFET temperature, () → (float64 °C, error)
	fmt.Println(must(chip.VoltageAux()))                                                                              // VAUX pin voltage, () → (float64 V, error), 0 ... 1.16
	fmt.Println(must(chip.ReadBlock()))                                                                               // Time-aligned BLOCK_READ, () → (LM25066Block, error)
	fmt.Println(must(chip.ReadAverage()))                                                                             // AVG_BLOCK_READ, () → (LM25066Block, error)
	fmt.Println(must(chip.BlackBox()))                                                                                // Snapshot at first SMBA, () → (LM25066Block, error)
	fmt.Println(must(chip.VoltageInAvg()), must(chip.VoltageOutAvg()))                                                // Averaged VIN / VOUT, () → (float64 V, error)
	fmt.Println(must(chip.CurrentAvg()), must(chip.PowerAvg()))                                                       // Averaged IIN / PIN, () → (float64, error)
	fmt.Println(must(chip.PowerPeak()))                                                                               // Peak input power, () → (float64 W, error)
	check(chip.ClearPowerPeak())                                                                                      // Reset the peak register, () → error
	check(chip.SetAveraging(6))                                                                                       // Average 2^6 samples, (exponent 0-12) → error
	fmt.Println(must(chip.GetAveraging()))                                                                            // () → (uint8, error)
	check(chip.SetVinUVWarn(fp(10.0)))                                                                                // VIN under-voltage warning, (*float64 volts, nil = off) → error
	check(chip.SetVinOVWarn(fp(15.0)))                                                                                // VIN over-voltage warning, (*float64 volts, nil = off) → error
	check(chip.SetVoutUVWarn(fp(9.0)))                                                                                // VOUT under-voltage warning, (*float64 volts, nil = off) → error
	check(chip.SetOTWarn(fp(100.0)))                                                                                  // Over-temperature warning, (*float64 celsius, nil = off) → error
	check(chip.SetOTFault(fp(125.0)))                                                                                 // Over-temperature fault, (*float64 celsius, nil = off) → error
	check(chip.SetIinWarn(fp(20.0)))                                                                                  // Input over-current warning, (*float64 amps, nil = off) → error
	check(chip.SetPinWarn(fp(200.0)))                                                                                 // Input over-power warning, (*float64 watts, nil = off) → error
	fmt.Printf("%+v\n", must(chip.GetLimits()))                                                                       // All thresholds, () → (LM25066Limits, error), nil = disabled
	fmt.Printf("%#04x %#06x\n", must(chip.StatusByte()), must(chip.StatusWord()))                                     // STATUS_BYTE / STATUS_WORD, () → (uint8 / uint16, error)
	fmt.Printf("%#04x %#04x\n", must(chip.StatusVout()), must(chip.StatusInput()))                                    // STATUS_VOUT / STATUS_INPUT, () → (uint8, error)
	fmt.Printf("%#04x %#04x %#04x\n", must(chip.StatusTemperature()), must(chip.StatusCML()), must(chip.StatusMfr())) // STATUS_TEMPERATURE / _CML / _MFR_SPECIFIC
	fmt.Printf("%#06x\n", must(chip.DiagnosticWord()))                                                                // READ_DIAGNOSTIC_WORD, () → (uint16, error)
	check(chip.ClearFaults())                                                                                         // CLEAR_FAULTS (does not turn the MOSFET on), () → error
	fmt.Println(must(chip.IsOn()))                                                                                    // MOSFET on, () → (bool, error)
	// check(chip.OperationOff())                                                        // CUTS THE LOAD RAIL, () → error
	// check(chip.OperationOn())                                                         // Switch the MOSFET on, () → error
	fmt.Printf("%#04x\n", must(chip.DeviceSetup())) // DEVICE_SETUP, () → (uint8, error)
	// check(chip.ConfigureDevice(&retry, &currentLimitHigh, nil))                       // Override RETRY/CL/CB pins, (*uint8, *bool, *bool) → error
	// check(chip.SetGateMask(0x04))                                                     // DANGEROUS: disable fault shut-off, (mask bits 5/4/2) → error
	fmt.Printf("%#04x %#04x\n", must(chip.GetGateMask()), must(chip.Capability()))      // GATE_MASK / CAPABILITY, () → (uint8, error)
	fmt.Println(must(chip.ManufacturerID()), must(chip.Model()), must(chip.Revision())) // MFR_ID / MFR_MODEL / MFR_REVISION, () → (string, error)
	check(chip.EnableInterrupt(power.LM25066SourceVinUVWarn))                           // Unmask an alert source, (source) → error
	check(chip.DisableInterrupt(power.LM25066SourceVinUVWarn))                          // Mask an alert source, (source) → error
	fmt.Printf("%#06x\n", must(chip.PollInterrupt()))                                   // Diagnostic word, () → (uint16, error)
	check(chip.OnInterrupt(func(diag uint16) { fmt.Printf("alert %#06x\n", diag) }))    // Subscribe to SMBA, (callback) → error
	time.Sleep(2 * time.Second)                                                         // Let the polling goroutine run
	check(chip.OffInterrupt())                                                          // Unsubscribe, () → error
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
