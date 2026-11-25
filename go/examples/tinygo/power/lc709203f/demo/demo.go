//go:build tinygo

// LC709203F demo example — TinyGo / Raspberry Pi Pico W.
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

	conn := connection.NewI2CConnection(i2c, 0x0B, nil, nil) // Create I2C connection, (i2c, addr=0x0B) → (*I2CConnection)
	chip, err := power.NewLC709203FFull(conn, 1000, 1)       // Create LC709203F driver, (connection, capacityMah, profile) → (*LC709203FFull, error)
	if err != nil {
		panic(err)
	}

	fmt.Printf("IC version %#04x, profile id %#04x, RSOC %d %%\n", must(chip.ICVersion()), must(chip.ProfileID()), must(chip.RSOC()))
	check(chip.SetAlarmRSOC(20))     // Alarm at 20 % RSOC, (percent) → error
	check(chip.SetAlarmVoltage(3.5)) // Alarm at 3.5 V, (volts) → error

	// Battery monitor: one line per 5 s for a minute, host temperature fixed at 25 °C
	for i := 0; i < 12; i++ {
		check(chip.SetTemperature(25.0))
		fmt.Printf("%.3f V  %.1f %%  %.1f C  alarm=%d\n", must(chip.Voltage()), must(chip.Percent()), must(chip.Temperature()), must(chip.PollInterrupt()))
		time.Sleep(5 * time.Second)
	}

	check(chip.Sleep())
	check(chip.Wake())
	fmt.Println("sleeping:", must(chip.IsSleeping()))
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
