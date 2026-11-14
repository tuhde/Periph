//go:build tinygo

// SHT31 complete example — TinyGo / Raspberry Pi Pico W.
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

	conn := connection.NewI2CConnection(i2c, 0x44, nil, nil) // Create I2C connection, (i2c, addr=0x44, ) → (*I2CConnection)

	chip, err := humidity.NewSHT31Full(conn) // Create SHT31 driver, (connection) → (*SHT31Full, error)
	chk(err)

	t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
	// single shot, high repeatability, CRC checked
	chk(err)
	fmt.Println(t, rh)
	tc, err := chip.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	chk(err)
	fmt.Println(tc)
	rhp, err := chip.ReadHumidity() // Read humidity only, () → (float32 %RH, error)
	chk(err)
	fmt.Println(rhp)
	rawT, rawRH, err := chip.ReadRaw() // Read raw words, () → (uint16, uint16, error)
	// 16-bit temperature and humidity ticks
	chk(err)
	fmt.Println(rawT, rawRH)

	chk(chip.SetRepeatability(humidity.SHT31RepeatabilityLow)) // Select repeatability, (level) → error
	// low / medium / high (4 / 6 / 15 ms)
	chk(chip.SetRepeatability(humidity.SHT31RepeatabilityHigh))
	chip.SetClockStretching(false) // Use clock-stretching commands, (on) → void
	// needs bus-layer support; off polls the NACKed read header

	chk(chip.StartPeriodic(2, humidity.SHT31RepeatabilityHigh, false)) // Start periodic mode, (rate, repeatability, art) → error
	// rate 0.5 / 1 / 2 / 4 / 10 mps; art = true selects the 4 mps ART mode
	time.Sleep(time.Second)
	ft, frh, ok, err := chip.Fetch() // Fetch the latest periodic result, () → (float32, float32, bool, error)
	// ok is false while the read header is NACKed
	chk(err)
	fmt.Println(ft, frh, ok)
	chk(chip.StopPeriodic()) // Stop periodic mode, () → error
	// break command 0x3093, waits 1 ms

	chk(chip.SetHeater(true)) // Switch the heater, (on) → error
	// about 5-25 mW; readings are unusable while on
	status, err := chip.ReadStatus() // Read status register, () → (uint16, error)
	// CRC verified; bit 13 = heater on
	chk(err)
	fmt.Printf("status 0x%04X\n", status)
	chk(chip.SetHeater(false))
	chk(chip.ClearStatus()) // Clear status flags, () → error
	// command 0x3041
	dp, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
	// one measurement, Magnus formula
	chk(err)
	fmt.Println(dp)
	chk(chip.SoftReset()) // Soft reset, () → error
	// 0x30A2, waits 1 ms, back to single shot

	for {
		time.Sleep(time.Second)
	}
}
