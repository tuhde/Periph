//go:build tinygo

// LC709203F complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every method of the Full driver once.
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

	fmt.Println(must(chip.Voltage()))                                            // Cell voltage, () → (float32 V, error)
	fmt.Println(must(chip.Percent()))                                            // State of charge (ITE), () → (float32 %, error)
	fmt.Println(must(chip.Temperature()))                                        // Cell temperature, () → (float32 °C, error)
	check(chip.SetTemperature(25.0))                                             // Host temperature (I²C mode), (celsius) → error; clamped to -20...+60 °C
	fmt.Println(must(chip.RSOC()))                                               // RSOC register, () → (uint8 %, error)
	check(chip.SetRSOC(50))                                                      // Force RSOC, (percent) → error
	check(chip.InitRSOC())                                                       // Initial RSOC from present voltage, () → error
	check(chip.BeforeRSOC())                                                     // RSOC from max voltage since reset, () → error
	check(chip.UseThermistor(3380))                                              // Thermistor mode, (bConstant) → error
	fmt.Println(must(chip.IsThermistorMode()))                                   // Temperature source, () → (bool, error)
	fmt.Println(must(chip.GetBConstant()))                                       // Thermistor B constant, () → (uint16 K, error)
	check(chip.UseI2CTemperature(25.0))                                          // Back to I²C temperature mode, (celsius) → error
	check(chip.SetAPA(0x19))                                                     // Adjustment Pack Application, (value) → error
	fmt.Println(must(chip.GetAPA()))                                             // () → (uint8, error)
	check(chip.SetAPT(0x1E))                                                     // Adjustment Pack Thermistor, (value) → error
	fmt.Println(must(chip.GetAPT()))                                             // () → (uint16, error)
	check(chip.SetCurrentDirection(power.LC709203FDirectionAuto))                // Current direction, (mode) → error; Auto / Charge / Discharge
	fmt.Println(must(chip.GetCurrentDirection()))                                // () → (uint16, error)
	check(chip.SetProfile(1))                                                    // Battery profile 0/1, (profile) → error; re-initializes the chip (90 ms)
	fmt.Println(must(chip.GetProfile()))                                         // () → (uint8, error)
	fmt.Printf("%#04x\n", must(chip.ProfileID()))                                // Number of the parameter, () → (uint16, error)
	fmt.Printf("%#04x\n", must(chip.ICVersion()))                                // IC version, () → (uint16, error)
	check(chip.SetAlarmRSOC(20))                                                 // Low-RSOC alarm, (percent) → error; 0 = off
	fmt.Println(must(chip.GetAlarmRSOC()))                                       // () → (uint8 %, error)
	check(chip.SetAlarmVoltage(3.5))                                             // Low-voltage alarm, (volts) → error; 0 = off
	fmt.Println(must(chip.GetAlarmVoltage()))                                    // () → (float32 V, error)
	fmt.Println(must(chip.PollInterrupt()))                                      // Alarm bitmask, () → (uint8, error); LC709203FAlarmRSOC = 1, LC709203FAlarmVoltage = 2
	check(chip.OnInterrupt(func(status uint8) { fmt.Println("alarm", status) })) // Subscribe to ALARMB, (callback) → error
	time.Sleep(2 * time.Second)                                                  // Let the polling goroutine run
	check(chip.OffInterrupt())                                                   // Unsubscribe, () → error
	check(chip.Sleep())                                                          // Sleep mode, () → error
	check(chip.Wake())                                                           // Wake up, () → error
	fmt.Println(must(chip.IsSleeping()))                                         // Power mode, () → (bool, error)
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
