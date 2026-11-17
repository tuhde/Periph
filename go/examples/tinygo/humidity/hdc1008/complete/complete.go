//go:build tinygo

// HDC1008 complete example — TinyGo / Raspberry Pi Pico W.
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

	chip := humidity.NewHDC1008Full(conn) // Create HDC1008 driver, (connection) → *HDC1008Full

	chk(chip.Init()) // Write default configuration, () → error
	// 0x1000: 14-bit T and RH, sequence mode, heater off
	serial, err := chip.SerialID() // Read the 40-bit serial ID, () → (uint64, error)
	chk(err)
	fmt.Printf("serial id 0x%010X\n", serial)
	cfg, err := chip.ReadConfig() // Read the configuration register, () → (uint16, error)
	chk(err)
	fmt.Printf("config 0x%04X\n", cfg)
	t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
	chk(err)
	// sequence mode, polls the NACKed read header (timeout 50 ms)
	fmt.Printf("%.2f C, %.1f %%RH\n", t, rh)
	tc, err := chip.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	chk(err)
	fmt.Println("temperature", tc)
	rhp, err := chip.ReadHumidity() // Read humidity only, () → (float32 %RH, error)
	chk(err)
	fmt.Println("humidity", rhp)
	rawT, rawRH, err := chip.ReadRaw() // Read raw words, () → (uint16, uint16, error)
	chk(err)
	// bits 1:0 zero
	fmt.Println("raw", rawT, rawRH)

	chk(chip.SetResolution(11, 8)) // Set resolution, (temperatureBits, humidityBits int) → error
	// T 14/11 bit, RH 14/11/8 bit
	fmt.Println("conversion time", chip.ConversionTime()) // Typical conversion time, () → float32 s
	chk(chip.SetResolution(14, 14))

	chk(chip.SetHeater(true)) // Switch the heater, (on bool) → error
	// runs only during measurements; readings drift while on
	on, err := chip.IsHeaterOn() // Heater state, () → (bool, error)
	chk(err)
	fmt.Println("heater on", on)
	chk(chip.SetHeater(false))
	low, err := chip.BatteryLow() // VDD below 2.8 V, () → (bool, error)
	chk(err)
	fmt.Println("battery low", low)
	chk(chip.SoftwareReset()) // Software reset, () → error
	// sets RST, waits 15 ms, rewrites the stored configuration

	chk(chip.OnInterrupt(func(t, rh float32) { fmt.Println("data ready", t, rh) })) // Subscribe to data ready, (callback) → error
	chk(conn.Write([]byte{0x00}))                                                   // trigger one measurement
	time.Sleep(100 * time.Millisecond)
	ready, err := chip.PollInterrupt() // Data ready check, () → (bool, error)
	chk(err)
	fmt.Println("ready", ready)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	for {
		time.Sleep(time.Second)
	}
}
