package main

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/component/resolver"
	mihomolog "github.com/metacubex/mihomo/log"
	"github.com/miekg/dns"
)

var errDNS = errors.New("域名解析失败")

const dnsTimeout = 700 * time.Millisecond

type dnsSettings struct {
	servers []string
}

type dnsPolicy struct {
	settings atomic.Pointer[dnsSettings]
}

type dnsContextKey struct{}
type systemResolver struct{}

func init() {
	// 上游适配器也使用当前请求的 DNS 设置来解析节点服务端。
	resolver.SystemResolver = systemResolver{}
	resolver.DisableIPv6 = false
	mihomolog.SetLevel(mihomolog.SILENT)
}

func resolveSettings(servers []string) (*dnsSettings, error) {
	if len(servers) > 8 {
		return nil, errors.New("DNS 地址数量超出上限")
	}
	result := &dnsSettings{}
	seen := make(map[string]bool)
	for _, server := range servers {
		server = strings.TrimSpace(server)
		var addr netip.AddrPort
		if ip, err := netip.ParseAddr(server); err == nil {
			addr = netip.AddrPortFrom(ip.Unmap(), 53)
		} else {
			addr, _ = netip.ParseAddrPort(server)
		}
		if !addr.IsValid() || addr.Port() == 0 || addr.Addr().IsUnspecified() || addr.Addr().IsMulticast() {
			return nil, errors.New("DNS 地址格式无效")
		}
		if canonical := addr.String(); !seen[canonical] {
			seen[canonical] = true
			result.servers = append(result.servers, canonical)
		}
	}
	return result, nil
}

func settingsFor(ctx context.Context) *dnsSettings {
	if policy, ok := ctx.Value(dnsContextKey{}).(*dnsPolicy); ok {
		if settings := policy.settings.Load(); settings != nil {
			return settings
		}
	}
	return &dnsSettings{}
}

func (systemResolver) ExchangeContext(ctx context.Context, q *dns.Msg) (*dns.Msg, error) {
	return settingsFor(ctx).exchange(ctx, q)
}

func exchangeDNS(ctx context.Context, server string, q *dns.Msg) (*dns.Msg, error) {
	client := &dns.Client{Net: "udp", Timeout: dnsTimeout}
	r, _, err := client.ExchangeContext(ctx, q, server)
	if err != nil {
		return nil, err
	}
	if r.Truncated {
		client.Net = "tcp"
		r, _, err = client.ExchangeContext(ctx, q, server)
	}
	return r, err
}

func usableAnswer(q, r *dns.Msg) bool {
	return r != nil && r.Response && r.Id == q.Id && !r.Truncated && len(q.Question) == 1 && len(r.Question) == 1 &&
		strings.EqualFold(r.Question[0].Name, q.Question[0].Name) && r.Question[0].Qtype == q.Question[0].Qtype &&
		r.Question[0].Qclass == q.Question[0].Qclass && (r.Rcode == dns.RcodeSuccess || r.Rcode == dns.RcodeNameError)
}

func (s *dnsSettings) exchange(ctx context.Context, q *dns.Msg) (*dns.Msg, error) {
	for _, server := range s.servers {
		r, err := exchangeDNS(ctx, server, q)
		if err == nil && usableAnswer(q, r) {
			return r, nil
		}
	}
	if androidRuntime {
		probe, cancel := context.WithTimeout(ctx, dnsTimeout)
		reply, err := androidDNS(probe, q)
		cancel()
		if err == nil && usableAnswer(q, reply) {
			return reply, nil
		}
		for _, server := range []string{"223.5.5.5:53", "1.1.1.1:53", "8.8.8.8:53"} {
			r, err := exchangeDNS(ctx, server, q)
			if err == nil && usableAnswer(q, r) {
				return r, nil
			}
		}
	} else if config, err := dns.ClientConfigFromFile("/etc/resolv.conf"); err == nil {
		for _, server := range config.Servers {
			r, err := exchangeDNS(ctx, net.JoinHostPort(server, config.Port), q)
			if err == nil && usableAnswer(q, r) {
				return r, nil
			}
		}
	}
	return nil, errDNS
}

func answerIPs(response *dns.Msg, name string, kind uint16) []netip.Addr {
	if response.Rcode != dns.RcodeSuccess {
		return nil
	}
	aliases := map[string]bool{strings.ToLower(name): true}
	// 只接受查询名及其 CNAME 链上的地址。
	for range min(len(response.Answer), 16) {
		before := len(aliases)
		for _, record := range response.Answer {
			if alias, ok := record.(*dns.CNAME); ok && aliases[strings.ToLower(alias.Hdr.Name)] {
				aliases[strings.ToLower(alias.Target)] = true
			}
		}
		if len(aliases) == before {
			break
		}
	}
	var ips []netip.Addr
	for _, record := range response.Answer {
		if !aliases[strings.ToLower(record.Header().Name)] || record.Header().Rrtype != kind {
			continue
		}
		switch a := record.(type) {
		case *dns.A:
			if ip, ok := netip.AddrFromSlice(a.A); ok {
				ips = append(ips, ip.Unmap())
			}
		case *dns.AAAA:
			if ip, ok := netip.AddrFromSlice(a.AAAA); ok {
				ips = append(ips, ip.Unmap())
			}
		}
	}
	return ips
}

func (s *dnsSettings) lookup(ctx context.Context, host, family string) ([]netip.Addr, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		ip = ip.Unmap()
		if family == "ip" || ip.Is4() && family == "ip4" || ip.Is6() && family == "ip6" {
			return []netip.Addr{ip}, nil
		}
		return nil, errDNS
	}
	name := normalizedHost(host)
	if name == "" {
		return nil, errDNS
	}
	if name == "localhost" || strings.HasSuffix(name, ".localhost") {
		switch family {
		case "ip4":
			return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
		case "ip6":
			return []netip.Addr{netip.IPv6Loopback()}, nil
		default:
			return []netip.Addr{netip.MustParseAddr("127.0.0.1"), netip.IPv6Loopback()}, nil
		}
	}
	if !androidRuntime && len(s.servers) == 0 {
		return net.DefaultResolver.LookupNetIP(ctx, family, name)
	}

	lookup := func(kind uint16) []netip.Addr {
		fqdn := dns.Fqdn(name)
		q := new(dns.Msg).SetQuestion(fqdn, kind)
		if response, err := s.exchange(ctx, q); err == nil {
			return answerIPs(response, fqdn, kind)
		}
		return nil
	}
	var ips []netip.Addr
	switch family {
	case "ip4":
		ips = lookup(dns.TypeA)
	case "ip6":
		ips = lookup(dns.TypeAAAA)
	default:
		v6 := make(chan []netip.Addr, 1)
		go func() { v6 <- lookup(dns.TypeAAAA) }()
		ips = append(lookup(dns.TypeA), <-v6...)
	}
	if len(ips) == 0 {
		return nil, errDNS
	}
	return ips, nil
}

func (systemResolver) LookupIP(ctx context.Context, host string) ([]netip.Addr, error) {
	return settingsFor(ctx).lookup(ctx, host, "ip")
}

func (systemResolver) LookupIPv4(ctx context.Context, host string) ([]netip.Addr, error) {
	return settingsFor(ctx).lookup(ctx, host, "ip4")
}

func (systemResolver) LookupIPv6(ctx context.Context, host string) ([]netip.Addr, error) {
	return settingsFor(ctx).lookup(ctx, host, "ip6")
}

func (r systemResolver) ResolveECH(ctx context.Context, host string) ([]byte, error) {
	host = normalizedHost(host)
	if host == "" {
		return nil, errDNS
	}
	q := new(dns.Msg).SetQuestion(dns.Fqdn(host), dns.TypeHTTPS)
	response, err := r.ExchangeContext(ctx, q)
	if err != nil {
		return nil, err
	}
	for _, answer := range response.Answer {
		if https, ok := answer.(*dns.HTTPS); ok {
			for _, field := range https.Value {
				if ech, ok := field.(*dns.SVCBECHConfig); ok {
					return ech.ECH, nil
				}
			}
		}
	}
	return nil, errDNS
}

func (systemResolver) Invalid() bool    { return false }
func (systemResolver) ClearCache()      {}
func (systemResolver) ResetConnection() {}

var tailnetIPv4 = netip.MustParsePrefix("100.64.0.0/10")

func bypassIP(addr netip.Addr) bool {
	addr = addr.Unmap()
	return addr.IsPrivate() || addr.IsLoopback() || addr.IsLinkLocalUnicast() ||
		addr.IsMulticast() || addr.IsUnspecified() || tailnetIPv4.Contains(addr)
}

// 内网命名空间没有解析结果时同样留在本地，避免把内部名称交给远端节点。
func localHostName(host string) bool {
	if _, err := netip.ParseAddr(host); err == nil {
		return false
	}
	return !strings.Contains(host, ".") || strings.HasSuffix(host, ".localhost") ||
		strings.HasSuffix(host, ".local") || strings.HasSuffix(host, ".lan") ||
		strings.HasSuffix(host, ".home.arpa") || strings.HasSuffix(host, ".ts.net")
}

// 目标分类只使用系统与活动链路视图，公共回退仅用于解析出站节点地址。
func (s *dnsSettings) targetQuery(ctx context.Context, host string, family uint16) ([]netip.Addr, bool) {
	name := dns.Fqdn(host)
	query := new(dns.Msg).SetQuestion(name, family)
	if androidRuntime {
		attempt, cancel := context.WithTimeout(ctx, dnsTimeout)
		reply, err := androidDNS(attempt, query)
		cancel()
		if err == nil && usableAnswer(query, reply) {
			return answerIPs(reply, name, family), true
		}
	}
	for _, server := range s.servers {
		reply, err := exchangeDNS(ctx, server, query)
		if err == nil && usableAnswer(query, reply) {
			return answerIPs(reply, name, family), true
		}
	}
	return nil, false
}

func classifyTarget(ctx context.Context, host string) (local []netip.Addr, public bool) {
	if ip, err := netip.ParseAddr(host); err == nil {
		if bypassIP(ip) {
			return []netip.Addr{ip}, false
		}
		return nil, true
	}
	probe, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	settings := settingsFor(ctx)
	var ips []netip.Addr
	var complete bool
	switch {
	case host == "localhost" || strings.HasSuffix(host, ".localhost"):
		return []netip.Addr{netip.MustParseAddr("127.0.0.1"), netip.IPv6Loopback()}, false
	case !androidRuntime && len(settings.servers) == 0:
		var err error
		ips, err = net.DefaultResolver.LookupNetIP(probe, "ip", host)
		complete = err == nil
	default:
		type result struct {
			addresses []netip.Addr
			ok        bool
		}
		other := make(chan result, 1)
		go func() {
			addresses, ok := settings.targetQuery(probe, host, dns.TypeAAAA)
			other <- result{addresses, ok}
		}()
		four, ok := settings.targetQuery(probe, host, dns.TypeA)
		six := <-other
		ips, complete = append(four, six.addresses...), ok && six.ok
	}
	for _, ip := range ips {
		if bypassIP(ip) {
			local = append(local, ip)
		}
	}
	return local, len(local) == 0 && complete && len(ips) > 0 && !localHostName(host)
}

func dialBypass(ctx context.Context, addresses []netip.Addr, port uint16) (net.Conn, error) {
	dialer := net.Dialer{Timeout: 3 * time.Second}
	for _, ip := range addresses {
		conn, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(ip.String(), strconv.Itoa(int(port))))
		if err == nil {
			return conn, nil
		}
	}
	return nil, errors.New("无法连接直连目标")
}
