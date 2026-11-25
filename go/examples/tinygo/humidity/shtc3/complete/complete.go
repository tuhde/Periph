//go:build tinygo

// SHTC3 complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every SHTC3Full method once.
package main

import (
	"fmt"
	"machine"

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

	conn := connection.NewI2CConnection(i2c, 0x70, nil, nil) // Create I2C connection, (i2c, addr=0x70, ) → (*I2CConnection)

	chip := humidity.NewSHTC3Full(conn) // Create SHTC3 driver, (connection) → *SHTC3Full
	chk(chip.Init())                    // Soft reset and ID check, () → error

	t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
	chk(err)                  // normal mode, both CRCs verified, one retry on a bad CRC
	fmt.Printf("%.2f C, %.1f %%RH\n", t, rh)
	h, err := chip.ReadHumidity() // Read humidity only, () → (float32 %RH, error)
	chk(err)
	fmt.Printf("humidity %.1f %%RH\n", h)
	tc, err := chip.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	chk(err)
	fmt.Printf("temperature %.2f C\n", tc)
	tr, rr, err := chip.ReadRaw() // Read raw codes, () → (uint16, uint16, error)
	chk(err)                      // (raw temperature, raw humidity), 16-bit
	fmt.Printf("raw %d %d\n", tr, rr)
	id, err := chip.ReadID() // Read the ID word, () → (uint16, error)
	chk(err)                 // (id & 0x083F) == 0x0807 for the SHTC3
	fmt.Printf("id 0x%04X\n", id)

	chip.SetLowPower(true) // Select low-power mode, (on) → void
	// 0.7 ms measurement, slightly noisier
	t, rh, err = chip.Read()
	chk(err)
	fmt.Printf("low power %.2f C %.1f %%RH\n", t, rh)
	chip.SetLowPower(false)

	chip.SetClockStretching(true) // Select clock stretching, (on) → void
	// needs a controller that tolerates SCL held low
	t, rh, err = chip.Read()
	chk(err)
	fmt.Printf("stretching %.2f C %.1f %%RH\n", t, rh)
	chip.SetClockStretching(false)

	chip.SetSleepBetween(false) // Sleep after every read?, (on) → void
	// off keeps the sensor idle (higher supply current)
	t, rh, err = chip.Read()
	chk(err)
	fmt.Printf("no sleep %.2f C %.1f %%RH\n", t, rh)
	chip.SetSleepBetween(true)
	chk(chip.Wake())            // Wake-up command, () → error
	chk(chip.Sleep())           // Sleep command, () → error
	dew, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
	chk(err)                    // one measurement, Magnus formula
	fmt.Printf("dew point %.2f C\n", dew)
	chk(chip.SoftReset()) // Soft reset and ID check, () → error
	// 0x805D, leaves the sensor idle
}
