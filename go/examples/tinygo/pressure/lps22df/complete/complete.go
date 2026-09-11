//go:build tinygo

// LPS22DF complete example — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	conn := connection.NewI2CConnection(i2c, 0x5C, nil, nil)
	lps, err := pressure.NewLPS22DFFull(conn, false)
	if err != nil {
		panic(err)
	}

	if err := lps.Configure(3, 0, false, 0, true); err != nil {
		println("configure:", err.Error())
	}
	if err := lps.OneShot(); err != nil {
		println("oneshot:", err.Error())
	}
	p, _ := lps.Pressure()
	t, _ := lps.Temperature()
	alt, _ := lps.Altitude(101325.0)
	_ = lps.SoftwareReset()
	_ = lps.SetPressureOffset(-50.0)
	_ = lps.SetPressureThreshold(102000.0)
	_ = lps.ConfigureInterrupt(false, false, true, false, true, false, false, false)
	_ = lps.ConfigurePressureEvent(true, false, false)
	_ = lps.Autozero()
	_ = lps.ResetReference()
	ref, _ := lps.ReferencePressure()
	_ = lps.SetFifoMode(pressure.LPS22DFFifoFifo)
	_ = lps.SetFifoWatermark(64)
	count, _ := lps.FifoSampleCount()
	samples := make([]float32, 128)
	n, _ := lps.ReadFifo(samples)
	src, _ := lps.InterruptSource()
	fmt.Printf("T=%.2f C, P=%.0f Pa, alt=%.1f m, ref=%.0f Pa, fifo=%d/%d, src=%+v\n",
		t, p, alt, ref, n, count, src)
}