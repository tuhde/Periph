//go:build tinygo

// ADS7828 complete example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.ADS7828I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x48) → *I2CConnection
	defer conn.Close()

	d, err := adcdac.NewADS7828Full(conn, 0) // Create ADS7828 driver, (conn, vref=0 → internal 2.5 V) → (*ADS7828Full, error)
	if err != nil {
		panic(err)
	}
	if err := d.Init(); err != nil { // Enable internal reference, () → error
		panic(err)
	}

	raw, err := d.ReadRaw(3) // Read CH3 raw code, (channel=0–7) → (uint16, error)
	// single-ended, interleaved channel encoding; returns 0–4095
	if err != nil {
		panic(err)
	}
	v3, err := d.ReadVoltage(3) // Read CH3 voltage, (channel=0–7) → (float32 V, error)
	// raw / 4096 × vref
	if err != nil {
		panic(err)
	}
	all, err := d.ReadAll() // Read all eight channels, () → ([8]float32 V, error)
	// one conversion per channel, CH0…CH7
	if err != nil {
		panic(err)
	}
	draw, err := d.ReadDifferentialRaw(0, false) // Read pair 0 raw code, (pair=0–3, inverted=false) → (uint16, error)
	// CH0 (+) / CH1 (−); a negative difference reads 0
	if err != nil {
		panic(err)
	}
	dv, err := d.ReadDifferential(1, true) // Read pair 1 inverted, (pair=0–3, inverted=false) → (float32 V, error)
	// CH3 (+) / CH2 (−)
	if err != nil {
		panic(err)
	}
	sv, err := d.ReadSignedDifferential(0) // Read pair 0 signed, (pair=0–3) → (float32 V, error)
	// two conversions (normal and inverted), range −vref … +vref
	if err != nil {
		panic(err)
	}
	lsb := d.LSB() // Get code step, () → float32 V
	// vref / 4096 (610 µV at 2.5 V)
	scan, err := d.ReadScan([]int{0, 2, 4, 6}) // Scan channels, (channels []int) → ([]uint16, error)
	// one raw conversion per channel in the given order
	if err != nil {
		panic(err)
	}
	err = d.SetReference(5.0) // Use external reference, (vref V; 0 = internal) → error
	// PD1 = 0 from now on; the reference must already be stable
	if err != nil {
		panic(err)
	}
	ref := d.GetReference()               // Get reference voltage, () → float32 V
	internal := d.UsesInternalReference() // Internal reference selected?, () → bool
	err = d.SetReference(0)               // Use internal reference, (vref V; 0 = internal) → error
	// re-runs the reference turn-on and 1 ms settle wait
	if err != nil {
		panic(err)
	}
	err = d.SetPowerMode(adcdac.ADS7828RefOnADCOn) // Set PD bits, (pd=0–3) → error
	// internal reference requires PD1 = 1 (values 2 or 3)
	if err != nil {
		panic(err)
	}
	err = d.PowerDown() // Power down completely, () → error
	// lone command with PD=00; next read re-enables the reference
	if err != nil {
		panic(err)
	}
	println(raw, int32(v3*1000), draw, int32(dv*1000), int32(sv*1000), int32(lsb*1000000), int32(ref*1000), internal, scan[0], int32(all[7]*1000))
	time.Sleep(10 * time.Millisecond)
}
