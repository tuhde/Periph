//go:build linux && !tinygo

// Max17048 complete example — Linux host.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x36"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x36) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := power.NewMax17048Full(conn, 1) // Create MAX17048 driver, (connection, cells) → (*Max17048Full, error)
	if err != nil {
		panic(err)
	}

	fmt.Println(must(chip.Voltage()))              // Cell voltage, () → (float32 V, error)
	fmt.Println(must(chip.Percent()))              // State of charge, () → (float32 %, error)
	fmt.Printf("%#04x\n", must(chip.RawVoltage())) // VCELL register, () → (uint16, error)
	fmt.Printf("%#04x\n", must(chip.RawSOC()))     // SOC register, () → (uint16, error)
	fmt.Println(must(chip.ChargeRate()))           // Charge/discharge rate, () → (float32 %/h, error)
	fmt.Printf("%#04x\n", must(chip.Version()))    // VERSION register, () → (uint16, error)
	fmt.Printf("%#02x\n", must(chip.ChipID()))     // Factory ID, () → (uint8, error)

	check(chip.SetRCOMP(0x97))              // RCOMP, (value) → error
	fmt.Println(must(chip.GetRCOMP()))      // () → (uint8, error)
	check(chip.CompensateTemperature(25.0)) // RCOMP from the temperature, (celsius) → error

	check(chip.SetAlertSOC(10))               // Empty alert threshold, (percent) → error; 1-32 %
	fmt.Println(must(chip.GetAlertSOC()))     // () → (uint8 %, error)
	check(chip.SetSOCChangeAlert(true))       // Alert on 1 % SOC change, (enabled) → error
	check(chip.SetVoltageAlert(3.3, 4.25))    // Voltage window, (minV, maxV) → error; 0 / 5.1 V disable a limit
	minV, maxV, err := chip.GetVoltageAlert() // () → (minV, maxV float32, error)
	check(err)
	fmt.Println(minV, maxV)
	check(chip.SetResetVoltage(3.0))          // Reset voltage, (volts) → error
	fmt.Println(must(chip.GetResetVoltage())) // () → (float32 V, error)
	check(chip.EnableVoltageResetAlert(true)) // Voltage reset asserts ALRT, (enabled) → error
	check(chip.SetComparatorDisabled(false))  // Hibernate comparator off, (disabled) → error

	check(chip.SetHibernateThresholds(26.624, 0.06)) // Hibernate thresholds, (hibThrPctPerH, actThrV) → error
	hib, act, err := chip.GetHibernateThresholds()   // () → (hibThrPctPerH, actThrV float32, error)
	check(err)
	fmt.Println(hib, act)
	check(chip.DisableHibernate())                   // Never hibernate, () → error
	check(chip.ForceHibernate())                     // Always hibernate, () → error
	check(chip.SetHibernateThresholds(26.624, 0.06)) // Restore the defaults
	fmt.Println(chip.IsHibernating())                // Hibernate status, () → bool

	fmt.Printf("%#04x\n", must(chip.ReadStatus()))     // STATUS register, () → (uint16, error)
	check(chip.ClearStatus(power.Max17048SourceReset)) // Clear flags and ALRT, (mask) → error
	cfg := must(chip.ReadConfig())                     // CONFIG register, () → (uint16, error)
	check(chip.WriteConfig(cfg))                       // Write CONFIG, (value) → error

	fmt.Printf("%#04x\n", must(chip.PollInterrupt())) // Alert sources, () → (uint16, error)
	// Max17048SourceReset / Overvoltage / Undervoltage
	// / VReset / LowSOC / SOCChange
	check(chip.EnableInterrupt(power.Max17048SourceSOCChange))                           // Enable a source, (source) → error
	check(chip.DisableInterrupt(power.Max17048SourceSOCChange))                          // Disable a source, (source) → error
	check(chip.OnInterrupt(func(status uint16) { fmt.Printf("alert %#04x\n", status) })) // Subscribe to ALRT, (callback) → error
	time.Sleep(2 * time.Second)
	check(chip.OffInterrupt()) // Unsubscribe, () → error

	check(chip.EnableSleep(true)) // Allow sleep mode, (enabled) → error
	check(chip.Sleep())           // Sleep, () → error
	check(chip.Wake())            // Wake up, () → error
	check(chip.EnableSleep(false))

	check(chip.QuickStart()) // Restart the SOC estimate, () → error; only if the first SOC is clearly wrong
	check(chip.Reset())      // Power-on reset, () → error; waits 1 s
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
