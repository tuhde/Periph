//go:build tinygo

// L3G4200D complete example — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/gyroscope"
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

	conn := connection.NewI2CConnection(i2c, 0x68, nil, nil) // Create I2C connection, (i2c, addr=0x68) → (*I2CConnection)
	chip, err := gyroscope.NewL3G4200DFull(conn, false)      // Create L3G4200D driver, (connection) → (*L3G4200DFull, error)
	if err != nil {
		panic(err)
	}

	cid, err := chip.WHOAMI() // Read WHO_AM_I, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	err = chip.Configure(gyroscope.L3G4200DODR200Hz, 0, gyroscope.L3G4200DFS500DPS) // Configure chip, (odr, bandwidth, full_scale) → error
	if err != nil {
		panic(err)
	}
	err = chip.EnableAxes(true, true, true) // Enable axes, (x, y, z) → error
	if err != nil {
		panic(err)
	}
	err = chip.SetFullScale(gyroscope.L3G4200DFS2000DPS) // Set full scale, (full_scale 250/500/2000) → error
	if err != nil {
		panic(err)
	}
	ready, err := chip.DataReady() // Check data ready, () → (bool, error)
	if err != nil {
		panic(err)
	}
	status, err := chip.Status() // Read STATUS, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	temp, err := chip.Temperature() // Read temperature, () → (int8, error)
	if err != nil {
		panic(err)
	}
	err = chip.EnableHighpass(0, 4) // Enable high-pass, (mode 0–3, cutoff 0–9) → error
	if err != nil {
		panic(err)
	}
	err = chip.DisableHighpass() // Disable high-pass, () → error
	if err != nil {
		panic(err)
	}
	err = chip.SetInterrupt(true, false, true, false, true, false, false, true) // Configure INT1, (...) → error
	if err != nil {
		panic(err)
	}
	err = chip.SetThreshold('x', 87.5) // Set X threshold, (axis 'x'/'y'/'z', threshold_dps) → error
	if err != nil {
		panic(err)
	}
	err = chip.SetDuration(4, false) // Set INT1 duration, (samples 0–127, wait=false) → error
	if err != nil {
		panic(err)
	}
	err = chip.SetDataReadyPin(true) // Route DRDY to INT2, (enable=true) → error
	if err != nil {
		panic(err)
	}
	err = chip.EnableFIFO(gyroscope.L3G4200DFIFOStream, 10) // Enable FIFO, (mode 0–4, watermark=0) → error
	if err != nil {
		panic(err)
	}
	err = chip.DisableFIFO() // Disable FIFO, () → error
	if err != nil {
		panic(err)
	}
	samples, err := chip.FIFOSamples() // Read FIFO count, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	err = chip.PowerDown() // Enter power-down, () → error
	if err != nil {
		panic(err)
	}
	err = chip.WakeUp() // Wake from power-down, () → error
	if err != nil {
		panic(err)
	}
	err = chip.Sleep() // Enter sleep mode, () → error
	if err != nil {
		panic(err)
	}
	intSrc, err := chip.ReadIntSource() // Read & clear INT1_SRC, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	x, y, z, err := chip.AngularRate() // Read X/Y/Z angular rate, () → (float32, float32, float32) rad/s
	if err != nil {
		panic(err)
	}
	fmt.Printf("X=%.2f Y=%.2f Z=%.2f rad/s, T=%d, ready=%v, status=0x%02X, fifo=%d, src=0x%02X, cid=0x%02X\n",
		x, y, z, temp, ready, status, samples, intSrc, cid)
}
