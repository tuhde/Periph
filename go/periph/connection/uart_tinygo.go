//go:build tinygo

// UARTConnection is the TinyGo implementation of the Connection interface
// for UART, backed by a configured machine.UART value the caller passes
// in. The machine.UART implements io.Reader/io.Writer directly.
package connection

import (
	"machine"
	"time"
)

// UARTConnection is a TinyGo machine.UART-backed implementation of
// Connection.
type UARTConnection struct {
	connectionBase
	uart  *machine.UART
	hasDE bool
	dePin machine.Pin // optional RS-485 DE pin
	baud  uint32
}

// NewUARTConnection binds a configured machine.UART and an optional
// RS-485 DE pin. Pass dePin as machine.Pin(0) (the zero value, which
// is the unconfigured/unused pin) if the chip is not RS-485. intPin and
// enPin may be nil if the device's INT/EN lines are not wired.
func NewUARTConnection(uart *machine.UART, dePin machine.Pin, intPin InputPin, enPin OutputPin) *UARTConnection {
	t := &UARTConnection{
		connectionBase: connectionBase{intPin: intPin, enPin: enPin},
		uart:            uart,
	}
	if dePin != 0 {
		dePin.Configure(machine.PinConfig{Mode: machine.PinOutput})
		dePin.Low()
		t.hasDE = true
		t.dePin = dePin
	}
	return t
}

// Close is a no-op: machine.UART has no explicit release.
func (t *UARTConnection) Close() error {
	return nil
}

// Write sends bytes to the device. For RS-485, asserts DE first, then
// waits the baud-rate-derived time before deasserting DE (TinyGo's
// machine.UART has no explicit TX-drain call).
func (t *UARTConnection) Write(data []byte) error {
	if !t.IsEnabled() {
		return nil
	}
	if len(data) == 0 {
		return nil
	}
	if t.hasDE {
		t.dePin.High()
	}
	n, err := t.uart.Write(data)
	_ = n
	if t.hasDE {
		// Wait for the bytes to leave the UART at the configured baud rate.
		// 10 bits per byte (8N1 + start + stop), plus a 100 µs safety margin.
		baud := uint32(9600)
		delay := time.Duration(len(data))*10*time.Second/time.Duration(baud) + 100*time.Microsecond
		time.Sleep(delay)
		t.dePin.Low()
	}
	return err
}

// Read reads n bytes from the device in a loop until n bytes accumulate
// or a 100 ms deadline elapses. machine.UART has no blocking
// read-exactly-n call.
func (t *UARTConnection) Read(n int) ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, n), nil
	}
	if n == 0 {
		return []byte{}, nil
	}
	buf := make([]byte, n)
	total := 0
	deadline := time.Now().Add(100 * time.Millisecond)
	for total < n {
		if time.Now().After(deadline) {
			break
		}
		nr, err := t.uart.Read(buf[total:])
		if err != nil {
			return nil, err
		}
		if nr == 0 {
			time.Sleep(1 * time.Millisecond)
			continue
		}
		total += nr
	}
	return buf[:total], nil
}

// WriteRead writes data then reads n bytes.
func (t *UARTConnection) WriteRead(data []byte, n int) ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, n), nil
	}
	if err := t.Write(data); err != nil {
		return nil, err
	}
	return t.Read(n)
}
