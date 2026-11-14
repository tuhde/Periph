//go:build tinygo

// TMP102 minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

// report prints a value in milli-units (TinyGo's println has no float formatting).
func report(label string, v float32) { println(label, "(milli)", int32(v*1000)) }

func sleep(ms int) { time.Sleep(time.Duration(ms) * time.Millisecond) }

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, temperature.TMP102I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	m := temperature.NewTMP102Minimal(conn) // Create TMP102 driver, (conn) → *TMP102Minimal

	for i := 0; i < 10; i++ {
		t, err := m.ReadTemperature() // Read temperature, () → (float32 °C, error)
		must(err)
		report("temperature", t)
		sleep(1000)
	}
}
