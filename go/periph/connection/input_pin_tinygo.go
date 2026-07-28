//go:build tinygo

// GpioInputPin is the TinyGo implementation of InputPin, backed by a
// hardware interrupt via machine.Pin.SetInterrupt.
package connection

import (
	"machine"
	"sync"
)

// GpioInputPin delivers edge notifications from a machine.Pin via a
// hardware interrupt armed for both edges. Registered handlers filter by
// their own requested Trigger in software — mirrored from the Linux
// gpiochip implementation — so a single physical pin can serve handlers
// with different trigger directions concurrently.
type GpioInputPin struct {
	pin machine.Pin

	mu       sync.Mutex
	handlers map[int]edgeHandlerEntry
	nextID   int
}

// NewGpioInputPin configures pin as a pulled-up input (typical for an
// open-drain INT line) and arms both-edge interrupt delivery.
func NewGpioInputPin(pin machine.Pin) *GpioInputPin {
	pin.Configure(machine.PinConfig{Mode: machine.PinInputPullup})
	p := &GpioInputPin{pin: pin, handlers: make(map[int]edgeHandlerEntry)}
	pin.SetInterrupt(machine.PinRising|machine.PinFalling, func(machine.Pin) {
		p.dispatch()
	})
	return p
}

func (p *GpioInputPin) dispatch() {
	trigger := Falling
	if p.pin.Get() {
		trigger = Rising
	}

	p.mu.Lock()
	toCall := make([]func(), 0, len(p.handlers))
	for _, h := range p.handlers {
		if h.trigger == Change || h.trigger == trigger {
			toCall = append(toCall, h.fn)
		}
	}
	p.mu.Unlock()

	for _, fn := range toCall {
		fn()
	}
}

// OnEdge registers handler to run on each edge matching trigger. Returns a
// function that removes it.
func (p *GpioInputPin) OnEdge(trigger Trigger, handler func()) (unsubscribe func()) {
	p.mu.Lock()
	id := p.nextID
	p.nextID++
	p.handlers[id] = edgeHandlerEntry{trigger: trigger, fn: handler}
	p.mu.Unlock()

	return func() {
		p.mu.Lock()
		delete(p.handlers, id)
		p.mu.Unlock()
	}
}

// Close disables the interrupt. TinyGo's machine.Pin has no explicit
// "unconfigure"; this simply stops future dispatch.
func (p *GpioInputPin) Close() error {
	p.pin.SetInterrupt(machine.PinRising|machine.PinFalling, nil)
	return nil
}
