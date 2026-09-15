//go:build linux && !tinygo

// LPS22DF complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5C"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	lps, err := pressure.NewLPS22DFFull(conn, false)
	if err != nil {
		panic(err)
	}

	if err := lps.Configure(3, 0, false, 0, true); err != nil {
		panic(err)
	}
	if err := lps.OneShot(); err != nil {
		panic(err)
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

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}