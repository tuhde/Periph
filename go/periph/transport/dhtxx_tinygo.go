//go:build tinygo

// DHTxxTransport is the TinyGo implementation of the DHT11/DHT22
// single-wire transport. Switches a machine.Pin between input and
// output to drive the start signal and read the response.
package transport

import (
	"fmt"
	"machine"
	"time"
)

// DHTxx timing constants.
const (
	dhtStartLowMs       = 20
	dhtBitThresholdUs   = 40
	dhtBitTimeoutUs     = 200
)

// DHTxxTransport is a TinyGo machine.Pin-backed DHTxx transport.
type DHTxxTransport struct {
	pin machine.Pin
}

// NewDHTxxTransport binds the data GPIO pin.
func NewDHTxxTransport(pin machine.Pin) *DHTxxTransport {
	return &DHTxxTransport{pin: pin}
}

// Close is a no-op on TinyGo.
func (t *DHTxxTransport) Close() error {
	return nil
}

// setOutput reconfigures the pin as output and drives it.
func (t *DHTxxTransport) setOutput(high bool) {
	t.pin.Configure(machine.PinConfig{Mode: machine.PinOutput})
	if high {
		t.pin.High()
	} else {
		t.pin.Low()
	}
}

// setInput reconfigures the pin as input.
func (t *DHTxxTransport) setInput() {
	t.pin.Configure(machine.PinConfig{Mode: machine.PinInput})
}

// read returns the current pin value (requires input configuration).
func (t *DHTxxTransport) read() bool {
	return t.pin.Get()
}

// Read executes the full start/response/bit-read sequence and returns
// the raw 5-byte frame.
func (t *DHTxxTransport) Read() ([]byte, error) {
	// 1. Host start signal: drive LOW for >=18 ms.
	t.setOutput(false)
	time.Sleep(dhtStartLowMs * time.Millisecond)
	// Release the bus (set as input, external pull-up brings it HIGH).
	t.setInput()
	time.Sleep(30 * time.Microsecond)

	// 2. Wait for sensor to pull DATA LOW.
	deadline := time.Now().Add(time.Microsecond * dhtBitTimeoutUs)
	for t.read() {
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("dhtxx: timeout waiting for response LOW")
		}
	}
	// Wait for sensor to release DATA HIGH.
	deadline = time.Now().Add(time.Microsecond * dhtBitTimeoutUs)
	for !t.read() {
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("dhtxx: timeout waiting for response HIGH")
		}
	}

	// 3. Read 40 bits.
	frame := make([]byte, 5)
	for byteIdx := 0; byteIdx < 5; byteIdx++ {
		for bitIdx := 7; bitIdx >= 0; bitIdx-- {
			// Wait for the LOW pre-bit pulse to end.
			deadline = time.Now().Add(time.Microsecond * dhtBitTimeoutUs)
			for !t.read() {
				if time.Now().After(deadline) {
					return nil, fmt.Errorf("dhtxx: timeout waiting for bit HIGH")
				}
			}
			// Measure the HIGH pulse.
			highStart := time.Now()
			deadline = time.Now().Add(time.Microsecond * dhtBitTimeoutUs)
			for t.read() {
				if time.Now().After(deadline) {
					return nil, fmt.Errorf("dhtxx: timeout waiting for bit LOW")
				}
			}
			if time.Since(highStart).Microseconds() > dhtBitThresholdUs {
				frame[byteIdx] |= 1 << bitIdx
			}
		}
	}
	return frame, nil
}
