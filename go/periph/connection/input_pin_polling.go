// PollingInputPin is a ticker-driven InputPin fallback used when a chip's
// Connection has no real GPIO line wired to its INT output. No build tag:
// it needs no GPIO access at all, just a time.Ticker, so it builds
// identically on Linux and TinyGo.
package connection

import (
	"sync"
	"time"
)

// DefaultPollInterval is the polling interval used by NewDefaultPollingInputPin,
// matching the 5 ms default used by the equivalent fallback in every other
// language in this repository.
const DefaultPollInterval = 5 * time.Millisecond

// PollingInputPin is a software fallback InputPin with no real signal to
// sample. Every registered handler is called once per tick regardless of
// its requested Trigger — the chip driver is expected to call
// PollInterrupt() from the handler and act on the returned status bits
// itself, rather than relying on electrical edge direction.
type PollingInputPin struct {
	interval time.Duration

	mu       sync.Mutex
	handlers map[int]func()
	nextID   int

	stop chan struct{}
}

// NewPollingInputPin starts a goroutine that fires all registered handlers
// once per interval.
func NewPollingInputPin(interval time.Duration) *PollingInputPin {
	p := &PollingInputPin{
		interval: interval,
		handlers: make(map[int]func()),
		stop:     make(chan struct{}),
	}
	go p.loop()
	return p
}

// NewDefaultPollingInputPin is NewPollingInputPin with the package's
// standard 5 ms polling interval.
func NewDefaultPollingInputPin() *PollingInputPin {
	return NewPollingInputPin(DefaultPollInterval)
}

func (p *PollingInputPin) loop() {
	ticker := time.NewTicker(p.interval)
	defer ticker.Stop()
	for {
		select {
		case <-p.stop:
			return
		case <-ticker.C:
			p.mu.Lock()
			toCall := make([]func(), 0, len(p.handlers))
			for _, fn := range p.handlers {
				toCall = append(toCall, fn)
			}
			p.mu.Unlock()
			for _, fn := range toCall {
				fn()
			}
		}
	}
}

// OnEdge registers handler to run on every tick. trigger is accepted for
// InputPin compatibility but has no effect, since PollingInputPin has no
// real signal to filter by direction.
func (p *PollingInputPin) OnEdge(trigger Trigger, handler func()) (unsubscribe func()) {
	_ = trigger
	p.mu.Lock()
	id := p.nextID
	p.nextID++
	p.handlers[id] = handler
	p.mu.Unlock()

	return func() {
		p.mu.Lock()
		delete(p.handlers, id)
		p.mu.Unlock()
	}
}

// Close stops the polling goroutine.
func (p *PollingInputPin) Close() error {
	close(p.stop)
	return nil
}
