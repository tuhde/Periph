//go:build linux && !tinygo

package connection

// GpioOutputPin drives a single GPIO line on /dev/gpiochip0 via the GPIO
// v1 character-device ioctls already used elsewhere in this package (see
// gpio_linux.go) — the same mechanism used internally by UARTConnection's
// DE pin, SIPOConnection's RCK/SRCLR/G, and DHTxxConnection's DATA line.
type GpioOutputPin struct {
	line int
}

// NewGpioOutputPin reserves line (a BCM line offset on /dev/gpiochip0) as
// an output.
func NewGpioOutputPin(line int) (*GpioOutputPin, error) {
	if err := requestGpioLines(line); err != nil {
		return nil, err
	}
	return &GpioOutputPin{line: line}, nil
}

// Set drives the line high or low.
func (p *GpioOutputPin) Set(high bool) error {
	return setGpioLine(p.line, high)
}

// Close releases the GPIO line reservation.
func (p *GpioOutputPin) Close() error {
	return releaseGpioLines(p.line)
}
