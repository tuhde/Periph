//go:build tinygo

// HTU21D-F complete example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the Full driver and calls every public method once.
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

	chk(chip.Init()) // Soft reset, () → error
	// 15 ms power-up wait, soft reset, 15 ms
	t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
	// no hold master, CRC checked, one retry on a mismatch
	chk(err)
	fmt.Println(t, rh)
	rhp, err := chip.ReadHumidity() // Read humidity only, () → (float32 %RH, error)
	chk(err)
	fmt.Println(rhp)
	tc, err := chip.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	chk(err)
	fmt.Println(tc)
	comp, err := chip.ReadHumidityCompensated() // Temperature-compensated humidity, () → (float32 %RH, error)
	// -0.15 %RH/°C relative to 25 °C
	chk(err)
	fmt.Println(comp)
	rawT, rawRH, err := chip.ReadRaw() // Read raw codes, () → (uint16, uint16, error)
	// (temperature, humidity), status bits cleared
	chk(err)
	fmt.Println(rawT, rawRH)

	chk(chip.SetResolution(3)) // Set resolution, (res int) → error
	// 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11
	res, err := chip.GetResolution() // Get resolution, () → (int, error)
	chk(err)
	fmt.Println("resolution", res)
	chk(chip.SetResolution(0))

	chk(chip.SetHeater(true)) // Switch the heater, (on bool) → error
	// about 5.5 mW; readings drift while on
	on, err := chip.Heater() // Heater state, () → (bool, error)
	chk(err)
	fmt.Println("heater on:", on)
	chk(chip.SetHeater(false))
	low, err := chip.BatteryLow() // End-of-battery flag, () → (bool, error)
	// true when VDD is below about 2.25 V
	chk(err)
	fmt.Println("battery low:", low)
	reg, err := chip.ReadUserRegister() // Read the user register, () → (byte, error)
	// 0x02 after reset
	chk(err)
	fmt.Printf("user register 0x%02X\n", reg)
	chip.SetHoldMaster(false) // Use hold-master commands, (on bool)
	// needs bus-layer clock stretching; off polls the NACKed read header
	dp, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
	// one measurement
	chk(err)
	fmt.Println("dew point", dp)
	chk(chip.SoftReset()) // Soft reset, () → error
	// 0xFE, waits 15 ms; the heater bit survives
	for {
		time.Sleep(time.Second)
	}
}
