//go:build tinygo

package main

import (
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
	"machine"
)

func checkTrue(label string, condition bool, passed, failed *int) {
	if condition {
		fmt.Printf("PASS %s\n", label)
		*passed++
	} else {
		fmt.Printf("FAIL %s\n", label)
		*failed++
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{
		Frequency: 100_000,
		SDA:       machine.GPIO4,
		SCL:       machine.GPIO5,
	})
	conn, err := connection.NewI2CConnection(machine.I2C0, 0x77, nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewBmp085Full(conn)
	if err != nil {
		panic(err)
	}

	passed := 0
	failed := 0

	// temperature() — must be in sensor operating range
	t, err := chip.Temperature()
	if err != nil {
		panic(err)
	}
	checkTrue("temperature() in range [-20, 85] °C", t >= -20.0 && t <= 85.0, &passed, &failed)

	// pressure() — valid range per datasheet (in Pa)
	p, err := chip.Pressure()
	if err != nil {
		panic(err)
	}
	checkTrue("pressure() in range [30000, 110000] Pa", p >= 30000.0 && p <= 110000.0, &passed, &failed)

	// altitude() — just checks it returns a float64 without throwing
	alt, err := chip.Altitude()
	if err != nil {
		panic(err)
	}
	checkTrue("altitude() returns float64", !math.IsNaN(alt) && !math.IsInf(alt, 0), &passed, &failed)

	// seaLevelPressure(0.0) — must yield a positive value
	slp, err := chip.SeaLevelPressure(0.0)
	if err != nil {
		panic(err)
	}
	checkTrue("seaLevelPressure(0.0) > 0", slp > 0.0, &passed, &failed)

	// chipID() — must return 0x55
	id, err := chip.ChipID()
	if err != nil {
		panic(err)
	}
	checkTrue("chipID() == 0x55", id == 0x55, &passed, &failed)

	// setOversampling(3) — must be accepted without exception
	chip.SetOversampling(3)
	checkTrue("setOversampling(3) accepted", true, &passed, &failed)

	// oversampling() — must reflect the value just set
	oss := chip.Oversampling()
	checkTrue("oversampling() == 3", oss == 3, &passed, &failed)

	// reset() — must complete without exception and keep sensor functional
	if err := chip.Reset(); err != nil {
		panic(err)
	}
	checkTrue("reset() accepted", true, &passed, &failed)

	// verify sensor still works after reset
	tAfter, err := chip.Temperature()
	if err != nil {
		panic(err)
	}
	checkTrue("temperature() after reset in range [-20, 85] °C",
		tAfter >= -20.0 && tAfter <= 85.0, &passed, &failed)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		// On TinyGo, os.Exit is not available, so we just loop
		for {
			time.Sleep(time.Second)
		}
	}
}