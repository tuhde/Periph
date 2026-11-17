//go:build tinygo

// LTC4151 complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every method of the Full driver once. Uses machine.I2C1 with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x6A, nil, nil) // Create I2C connection, (i2c, addr=0x6A) → (*I2CConnection)
	chip, err := power.NewLTC4151Full(conn, 0.02)            // Create LTC4151 driver, (connection, rSense=0.02 Ω) → (*LTC4151Full, error)
	if err != nil {
		panic(err)
	}

	must := func(err error) {
		if err != nil {
			panic(err)
		}
	}

	v, err := chip.Voltage() // Read supply voltage, () → (float32 V, error)
	must(err)                // VIN code × 25 mV
	fmt.Println(v)
	c, err := chip.Current() // Read load current, () → (float32 A, error)
	must(err)                // sense voltage / rSense
	fmt.Println(c)
	p, err := chip.Power() // Read load power, () → (float32 W, error)
	must(err)              // one block read of SENSE and VIN
	fmt.Println(p)
	sv, err := chip.SenseVoltage() // Read SENSE+ − SENSE−, () → (float32 V, error)
	must(err)
	fmt.Println(sv)
	av, err := chip.AdinVoltage() // Read ADIN pin voltage, () → (float32 V, error)
	must(err)
	fmt.Println(av)
	r, err := chip.ReadAll() // Consistent read of all channels, () → (LTC4151Readings, error)
	must(err)                // one block read of registers 0x00–0x05
	fmt.Printf("%+v\n", r)
	raw, err := chip.ReadRaw(power.LTC4151Vin) // Read raw 12-bit code, (channel 0–2) → (uint16, error)
	must(err)                                  // LTC4151Sense = 0, LTC4151Vin = 1, LTC4151Adin = 2
	fmt.Println(raw)

	must(chip.SetSenseResistor(0.01))    // Change sense resistor, (ohms) → error
	fmt.Println(chip.GetSenseResistor()) // Read sense resistor, () → float32 Ω
	must(chip.SetSenseResistor(0.02))

	s, err := chip.Snapshot(power.LTC4151Sense) // One snapshot conversion, (channel 0–2) → (float32 V, error)
	must(err)                                   // returns to continuous scan; ErrLTC4151Timeout after 100 ms
	fmt.Println(s)
	m, err := chip.IsSnapshotMode() // Snapshot mode active, () → (bool, error)
	must(err)
	fmt.Println(m)
	b, err := chip.IsBusy(power.LTC4151Vin) // Channel busy, (channel 0–2) → (bool, error)
	must(err)
	fmt.Println(b)
	must(chip.SetContinuous()) // Back to continuous scan, () → error

	must(chip.SetStuckBusTimer(true)) // Stuck-bus reset timer, (enabled) → error
	must(chip.SetPageRW(true))        // I²C page read/write, (enabled) → error
	ctrl, err := chip.ReadControl()   // Read CONTROL, () → (uint8, error)
	must(err)
	fmt.Printf("0x%02X\n", ctrl)
	must(chip.WriteControl(0x0C)) // Write CONTROL, (value) → error; test mode bit 4 is always masked to 0

	fmt.Println(power.LTC4151TemperatureFromAdin(900, 1200)) // NTC example circuit, (adinCode, vinCode) → float32 °C
}
