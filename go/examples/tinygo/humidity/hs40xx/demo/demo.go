//go:build tinygo

// HS40xx demo example — TinyGo / Raspberry Pi Pico W.
//
// Humidity watchdog: periodic mode with an RH-high alert.
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

	conn := connection.NewI2CConnection(i2c, 0x54, nil, nil) // Create I2C connection, (i2c, addr=0x54, ) → (*I2CConnection)

	chip := humidity.NewHS40xxFull(conn) // Create HS40xx driver, (connection) → *HS40xxFull

	// --- Humidity watchdog ---
	// Single readings for 10 s, then periodic mode with an RH-high alert (breathe on the sensor).
	id, err := chip.ReadID() // Read the 32-bit sensor ID, () → (uint32, error)
	chk(err)
	fmt.Printf("ID %#x\n", id)
	for n := 0; n < 5; n++ {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
		chk(err)
		fmt.Printf("%.2f C, %.1f %%RH\n", t, rh)
		time.Sleep(2 * time.Second)
	}

	rhHigh, rhReset := float32(60), float32(55)
	chk(chip.SetAlertThresholds(humidity.HS40xxThresholds{RHHigh: &rhHigh, RHHighReset: &rhReset})) // Set alert thresholds, (HS40xxThresholds) → error
	chk(chip.EnableInterrupt(humidity.HS40xxSourceRHHigh))                                          // Enable alert sources, (source byte) → error
	chk(chip.StartPeriodic(1))                                                                      // Start periodic mode, (periodS float64) → error
	for n := 0; n < 15; n++ {
		time.Sleep(time.Second)
		p, err := chip.ReadPeriodic() // Fetch the latest periodic result, () → (HS40xxPeriodic, error)
		chk(err)
		alert := "ok"
		if p.Status&humidity.HS40xxSourceRHHigh != 0 {
			alert = "ALERT"
		}
		fmt.Printf("%.2f C, %.1f %%RH  %s\n", p.TemperatureC, p.HumidityPct, alert)
	}

	chk(chip.StopPeriodic()) // Stop periodic mode, () → error
	chk(chip.SoftReset())    // Soft reset, () → error
}
