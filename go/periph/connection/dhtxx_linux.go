//go:build linux && !tinygo

// DHTxxConnection is the Linux implementation of the DHT11/DHT22
// single-wire connection. Drives a GPIO on /dev/gpiochip0 via the
// chardev ioctls and decodes the sensor's 40-bit response.
package connection

import (
	"fmt"
	"time"
)

// DHTxx timing constants.
const (
	dhtStartLowMs        = 20
	dhtReleaseWaitUs     = 30
	dhtResponseTimeoutUs = 200
	dhtBitTimeoutUs      = 200
	dhtBitThresholdUs    = 40
)

// DHTxxConnection is a Linux /dev/gpiochip0-backed DHTxx connection. It
// does not implement the Connection interface — DHTxx's single-wire
// protocol has no separate write/writeRead phase — but embeds
// connectionBase for the same Enable/Disable/IsEnabled/EnPin gating every
// other connection in this package shares. There is no INT line, so IntPin
// is always nil.
type DHTxxConnection struct {
	connectionBase
	dataLine int
	twoPin   bool
	dataOut  int // optional open-drain output line for two-pin variant
}

// NewDHTxxConnection reserves the data GPIO line on /dev/gpiochip0. Pass
// dataOutLine = -1 for the single-pin variant, or the open-drain output
// line offset for the two-pin variant (faster direction switching). enPin
// may be nil if the device's EN line is not wired.
func NewDHTxxConnection(dataLine, dataOutLine int, enPin OutputPin) (*DHTxxConnection, error) {
	if dataOutLine >= 0 {
		if err := requestGpioLines(dataLine, dataOutLine); err != nil {
			return nil, err
		}
	} else {
		if err := requestGpioLines(dataLine); err != nil {
			return nil, err
		}
	}
	return &DHTxxConnection{
		connectionBase: connectionBase{enPin: enPin},
		dataLine:        dataLine,
		dataOut:         dataOutLine,
		twoPin:          dataOutLine >= 0,
	}, nil
}

// Close releases the GPIO line reservation.
func (t *DHTxxConnection) Close() error {
	return releaseGpioLines(t.dataLine, t.dataOut)
}

// Read executes the full start/response/bit-read sequence and returns
// the raw 5-byte frame. Returns a zero-filled frame without touching the
// bus when disabled.
func (t *DHTxxConnection) Read() ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, 5), nil
	}
	// 1. Host start signal: drive LOW for >=18 ms.
	if t.twoPin {
		// Two-pin: drive open-drain output low, input line reads it.
		_ = setGpioLine(t.dataOut, true) // high impedance = high
	} else {
		_ = setGpioLine(t.dataLine, false)
	}
	time.Sleep(dhtStartLowMs * time.Millisecond)
	if t.twoPin {
		_ = setGpioLine(t.dataOut, false) // actively drive low
	} else {
		_ = setGpioLine(t.dataLine, false)
	}
	time.Sleep(dhtStartLowMs * time.Millisecond)
	if t.twoPin {
		_ = setGpioLine(t.dataOut, true) // release
	} else {
		_ = setGpioLine(t.dataLine, true) // release
	}
	time.Sleep(time.Microsecond * dhtReleaseWaitUs)

	// 2. Wait for sensor to pull DATA LOW (response start).
	deadline := time.Now().Add(time.Microsecond * dhtResponseTimeoutUs)
	for {
		v, _ := readGpioLine(t.dataLine)
		if !v {
			break // LOW detected
		}
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("dhtxx: timeout waiting for response LOW")
		}
		time.Sleep(time.Microsecond)
	}
	// Wait for sensor to release DATA HIGH (response start of data).
	deadline = time.Now().Add(time.Microsecond * dhtResponseTimeoutUs)
	for {
		v, _ := readGpioLine(t.dataLine)
		if v {
			break
		}
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("dhtxx: timeout waiting for response HIGH")
		}
		time.Sleep(time.Microsecond)
	}

	// 3. Read 40 bits. Each bit starts with a ~50 µs LOW, then a
	//    ~26 µs HIGH (0) or ~70 µs HIGH (1).
	frame := make([]byte, 5)
	for byteIdx := 0; byteIdx < 5; byteIdx++ {
		for bitIdx := 7; bitIdx >= 0; bitIdx-- {
			// Wait for the LOW pre-bit pulse to end.
			deadline = time.Now().Add(time.Microsecond * dhtBitTimeoutUs)
			for {
				v, _ := readGpioLine(t.dataLine)
				if v {
					break
				}
				if time.Now().After(deadline) {
					return nil, fmt.Errorf("dhtxx: timeout waiting for bit HIGH")
				}
				time.Sleep(time.Microsecond)
			}
			// Measure the duration of the HIGH pulse.
			highStart := time.Now()
			deadline = time.Now().Add(time.Microsecond * dhtBitTimeoutUs)
			for {
				v, _ := readGpioLine(t.dataLine)
				if !v {
					break
				}
				if time.Now().After(deadline) {
					return nil, fmt.Errorf("dhtxx: timeout waiting for bit LOW")
				}
				time.Sleep(time.Microsecond)
			}
			highDur := time.Since(highStart)
			if highDur.Microseconds() > dhtBitThresholdUs {
				frame[byteIdx] |= 1 << bitIdx
			}
		}
	}
	return frame, nil
}
