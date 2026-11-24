//go:build !linux

package main

import (
	"context"

	"github.com/miekg/dns"
)

const androidRuntime = false

func androidDNS(context.Context, *dns.Msg) (*dns.Msg, error) {
	return nil, errDNS
}
