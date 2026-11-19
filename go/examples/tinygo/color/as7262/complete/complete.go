//go:build tinygo

// AS7262 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every AS7262 API method once.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/color"
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

	conn := connection.NewI2CConnection(i2c, 0x49, nil, nil) // Create I2C connection, (i2c, addr=0x49, ) → (*I2CConnection)

	chip, err := color.NewAS7262Full(conn) // Create AS7262 driver, (connection) → (*AS7262Full, error)
	chk(err)

	c, err := chip.Read() // Read calibrated channels, () → (AS7262Channels[float32], error)
	// one one-shot conversion
	chk(err)
	fmt.Println(c)
	r, err := chip.ReadRaw() // Read raw counts, () → (AS7262Channels[uint16], error)
	chk(err)
	fmt.Println(r)
	t, err := chip.Temperature() // Read device temperature, () → (float32 °C, error)
	chk(err)
	fmt.Println(t)
	chk(chip.Measure()) // Start a one-shot conversion, () → error
	// does not wait
	for {
		ok, err := chip.DataReady() // Check data ready, () → (bool, error)
		chk(err)
		if ok {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	c, err = chip.ReadCalibrated() // Read last calibrated results, () → (AS7262Channels[float32], error)
	chk(err)
	fmt.Println(c)
	r, err = chip.ReadRawLast() // Read last raw counts, () → (AS7262Channels[uint16], error)
	chk(err)
	fmt.Println(r)
	v, err := chip.ReadChannel(5) // Read one channel, (index int) → (float32, error)
	// 0..5 = V, B, G, Y, O, R
	chk(err)
	fmt.Println(v)
	raw, err := chip.ReadChannelRaw(5) // Read one raw channel, (index int) → (uint16, error)
	chk(err)
	fmt.Println(raw)
	chk(chip.SetGain(16)) // Set gain, (gain float32) → error
	// 0-3 index or 1, 3.7, 16, 64
	g, err := chip.GetGain() // Get gain, () → (float32, error)
	chk(err)
	fmt.Println(g)
	chk(chip.SetIntegrationTime(165.2)) // Set integration time, (ms float64) → error
	// 2.8 - 714 ms
	ms, err := chip.GetIntegrationTime() // Get integration time, () → (float64 ms, error)
	chk(err)
	fmt.Println(ms)
	chk(chip.SetMode(3)) // Set bank mode, (mode int) → error
	// 0, 1, 2 continuous; 3 one-shot
	m, err := chip.GetMode() // Get bank mode, () → (int, error)
	chk(err)
	fmt.Println(m)
	chk(chip.SetContinuous(2)) // Continuous conversions, (mode int) → error
	chk(chip.SetMode(3))
	chk(chip.SetInterrupt(false)) // Enable/disable INT pin, (enabled bool) → error
	chk(chip.SetLED(true, 25))    // Driver LED, (on bool, currentMA float32) → error
	// 12.5, 25, 50 or 100 mA
	chk(chip.SetLED(false, 25))
	chk(chip.SetIndicatorLED(true, 4)) // Indicator LED, (on bool, currentMA float32) → error
	// 1, 2, 4 or 8 mA
	chk(chip.SetIndicatorLED(false, 4))
	ht, hv, err := chip.HardwareVersion() // Hardware version, () → (type, version byte, error)
	chk(err)
	fmt.Println(ht, hv)
	maj, min, sub, err := chip.FirmwareVersion() // Firmware version, () → (major, minor, sub byte, error)
	chk(err)
	fmt.Println(maj, min, sub)
	dt, err := chip.DeviceType() // Device type, () → (byte, error)   (0x40)
	chk(err)
	fmt.Printf("%#x\n", dt)
	vr, err := chip.ReadVirtual(0x05) // Read a virtual register, (reg byte) → (byte, error)
	chk(err)
	fmt.Println(vr)
	chk(chip.WriteVirtual(0x05, 0x3B)) // Write a virtual register, (reg, value byte) → error
	chk(chip.SetContinuous(2))
	chk(chip.OnInterrupt(func(status byte) { // Subscribe to INT, (callback func(status byte)) → error
		c, _ := chip.ReadCalibrated()
		fmt.Println("data ready", c)
	}))
	time.Sleep(3 * time.Second)
	st, err := chip.PollInterrupt() // Poll DATA_RDY, () → (byte, error)
	chk(err)
	fmt.Println(st)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	chk(chip.SoftReset())    // Soft reset, () → error
	// restores defaults
}
