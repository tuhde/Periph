//go:build linux && !tinygo

// AS7262 demo example — Linux host.
//
// Spectrometer demo: gain sweep, integration times, LED reflectance, continuous mode.
package main

import (
	"fmt"
	"os"
	"strconv"
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
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x49"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x49) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := color.NewAS7262Full(conn) // Create AS7262 driver, (connection) → (*AS7262Full, error)
	chk(err)

	// --- Spectrometer ---
	// Versions, gain sweep, integration time comparison, LED reflectance difference, continuous mode.
	dt, err := chip.DeviceType() // Device type, () → (byte, error)
	chk(err)
	ht, hv, _ := chip.HardwareVersion()        // Hardware version, () → (type, version byte, error)
	maj, min, sub, _ := chip.FirmwareVersion() // Firmware version, () → (major, minor, sub byte, error)
	fmt.Printf("%#x hw %d/%d fw %d.%d.%d\n", dt, ht, hv, maj, min, sub)
	t, err := chip.Temperature() // Read device temperature, () → (float32 °C, error)
	chk(err)
	fmt.Printf("chip %.0f C\n", t)

	for _, gain := range []float32{1, 3.7, 16, 64} {
		chk(chip.SetGain(gain)) // Set gain, (gain float32) → error
		g, _ := chip.GetGain()  // Get gain, () → (float32, error)
		fmt.Println("gain", g)
		c, err := chip.Read() // Read calibrated, () → (AS7262Channels[float32], error)
		chk(err)
		fmt.Println(" cal", c)
		r, err := chip.ReadRaw() // Read raw, () → (AS7262Channels[uint16], error)
		chk(err)
		fmt.Println(" raw", r)
	}
	chk(chip.SetGain(16))

	for _, ms := range []float64{50, 500} {
		chk(chip.SetIntegrationTime(ms)) // Set integration time, (ms float64) → error
		t0 := time.Now()
		_, err := chip.Read()
		chk(err)
		got, _ := chip.GetIntegrationTime() // Get integration time, () → (float64 ms, error)
		fmt.Println("integration", got, "ms ->", time.Since(t0))
	}
	chk(chip.SetIntegrationTime(165.2))

	chk(chip.SetMode(3)) // Set bank mode, (mode int) → error
	base, err := chip.Read()
	chk(err)
	chk(chip.SetIndicatorLED(true, 4)) // Indicator LED, (on bool, currentMA float32) → error
	chk(chip.SetLED(true, 25))         // Driver LED, (on bool, currentMA float32) → error
	lit, err := chip.Read()
	chk(err)
	chk(chip.SetLED(false, 25))
	chk(chip.SetIndicatorLED(false, 4))
	names := [6]string{"violet", "blue", "green", "yellow", "orange", "red"}
	b, l := base.Array(), lit.Array()
	best := 0
	for i := range b {
		if l[i]-b[i] > l[best]-b[best] {
			best = i
		}
	}
	fmt.Println("reflectance dominant:", names[best])

	chk(chip.Measure()) // Start one-shot, () → error
	for {
		ok, err := chip.DataReady() // Data ready, () → (bool, error)
		chk(err)
		if ok {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	cal, _ := chip.ReadCalibrated() // Last calibrated results, () → (AS7262Channels[float32], error)
	rawl, _ := chip.ReadRawLast()   // Last raw counts, () → (AS7262Channels[uint16], error)
	ch0, _ := chip.ReadChannel(0)   // One channel, (index int) → (float32, error)
	fmt.Println(cal, rawl, ch0)

	chk(chip.SetContinuous(2))               // Continuous mode, (mode int) → error
	chk(chip.OnInterrupt(func(status byte) { // Subscribe to INT, (callback func(status byte)) → error
		c, _ := chip.ReadCalibrated()
		fmt.Println(c)
	}))
	time.Sleep(5 * time.Second)
	st, _ := chip.PollInterrupt() // Poll DATA_RDY, () → (byte, error)
	fmt.Println(st)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	chk(chip.SetMode(3))
	chk(chip.WriteVirtual(0x05, 0x3B)) // Write virtual register, (reg, value byte) → error
	v, _ := chip.ReadVirtual(0x05)     // Read virtual register, (reg byte) → (byte, error)
	fmt.Println(v)
	chk(chip.SoftReset()) // Soft reset, () → error
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
