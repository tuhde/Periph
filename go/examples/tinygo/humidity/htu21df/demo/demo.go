//go:build tinygo

// HTU21D-F demo example — TinyGo / Raspberry Pi Pico W.
//
// Humidity logger with heater self-check: five readings with dew point, 8/12 bit
// comparison and a 10 s heater test.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x40, nil, nil) // Create I2C connection, (i2c, addr=0x40, ) → (*I2CConnection)

	chip := humidity.NewHTU21DFFull(conn) // Create HTU21D-F driver, (connection) → *HTU21DFFull

	chk(chip.Init())                    // Soft reset, () → error
	chk(chip.SoftReset())               // Soft reset, () → error
	reg, err := chip.ReadUserRegister() // Read the user register, () → (byte, error)
	chk(err)
	res, err := chip.GetResolution() // Get resolution, () → (int, error)
	chk(err)
	fmt.Printf("user register 0x%02X, resolution %d\n", reg, res)

	// --- Five readings at 12/14 bit ---
	for i := 0; i < 5; i++ {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32, float32, error)
		chk(err)
		comp, err := chip.ReadHumidityCompensated() // () → (float32 %RH, error)
		chk(err)
		dp, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
		chk(err)
		fmt.Printf("%.2f C %.1f %%RH (compensated %.1f) dew point %.2f C\n", t, rh, comp, dp)
		time.Sleep(2 * time.Second)
	}
	rawT, rawRH, err := chip.ReadRaw() // Read raw codes, () → (uint16, uint16, error)
	chk(err)
	fmt.Println("raw", rawT, rawRH)

	// --- 8/12 bit: shorter conversion, coarser resolution ---
	chk(chip.SetResolution(1)) // Set resolution, (res int) → error
	start := time.Now()
	tc, err := chip.ReadTemperature() // () → (float32 °C, error)
	chk(err)
	rhc, err := chip.ReadHumidity() // () → (float32 %RH, error)
	chk(err)
	fmt.Printf("8/12 bit: %.2f C %.1f %%RH in %v\n", tc, rhc, time.Since(start))
	chk(chip.SetResolution(0))
	chip.SetHoldMaster(false) // Use hold-master commands, (on bool)

	// --- Heater self-check: 10 s ---
	rhBefore, err := chip.ReadHumidity()
	chk(err)
	tBefore, err := chip.ReadTemperature()
	chk(err)
	chk(chip.SetHeater(true)) // Switch the heater, (on bool) → error
	time.Sleep(10 * time.Second)
	on, err := chip.Heater() // Heater state, () → (bool, error)
	chk(err)
	fmt.Println("heater on:", on)
	rhAfter, err := chip.ReadHumidity()
	chk(err)
	tAfter, err := chip.ReadTemperature()
	chk(err)
	fmt.Printf("RH drop %.1f %%RH, temperature rise %.2f K\n", rhBefore-rhAfter, tAfter-tBefore)
	chk(chip.SetHeater(false))
	low, err := chip.BatteryLow() // End-of-battery flag, () → (bool, error)
	chk(err)
	fmt.Println("battery low:", low)
	for {
		time.Sleep(time.Second)
	}
}
