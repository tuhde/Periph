//go:build tinygo

// Max17048 demo example — TinyGo / Raspberry Pi Pico W.
//
// Battery monitor on a 1000 mAh 3.7 V LiPo: IDs, alarms at 20 % / 3.5 V,
// twelve readings 5 s apart, then sleep / wake.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x36, nil, nil) // Create I2C connection, (i2c, addr=0x36) → (*I2CConnection)
	chip, err := power.NewMax17048Full(conn, 1)              // Create MAX17048 driver, (connection, cells) → (*Max17048Full, error)
	if err != nil {
		panic(err)
	}

	fmt.Printf("version %#04x, id %#02x\n", must(chip.Version()), must(chip.ChipID()))
	check(chip.ClearStatus(power.Max17048SourceReset)) // Clear the reset indicator, (mask) → error

	// Battery fuel gauge: one line per second for 30 s
	for n := 0; n < 30; n++ {
		fmt.Printf("%.3f V  %.1f %%  %+.1f %%/h\n", must(chip.Voltage()), must(chip.Percent()), must(chip.ChargeRate()))
		time.Sleep(time.Second)
	}

	check(chip.SetAlertSOC(10))                                                          // Low-SOC alert at 10 %, (percent) → error
	check(chip.SetVoltageAlert(3.3, 4.25))                                               // Voltage window 3.3-4.25 V, (minV, maxV) → error
	check(chip.OnInterrupt(func(status uint16) { fmt.Printf("alert %#04x\n", status) })) // Subscribe to ALRT, (callback) → error
	time.Sleep(5 * time.Second)
	check(chip.OffInterrupt())
	fmt.Printf("poll_interrupt: %#04x\n", must(chip.PollInterrupt())) // Without an ALRT pin, () → (uint16, error)

	for _, celsius := range []float32{5, 35, 20} {
		check(chip.CompensateTemperature(celsius)) // RCOMP for the temperature, (celsius) → error
		fmt.Printf("RCOMP at %.0f C: %#02x\n", celsius, must(chip.GetRCOMP()))
	}

	check(chip.DisableHibernate()) // Never hibernate, () → error
	fmt.Println("hibernating:", chip.IsHibernating())
	check(chip.SetHibernateThresholds(26.624, 0.06)) // Back to the defaults

	check(chip.EnableSleep(true)) // Sleep round trip, (enabled) → error
	check(chip.Sleep())
	time.Sleep(time.Second)
	check(chip.Wake())
	check(chip.EnableSleep(false))
	fmt.Printf("after wake: %.3f V\n", must(chip.Voltage()))
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
