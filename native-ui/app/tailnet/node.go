package main

import (
	"context"
	"errors"
	"net"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/types/logger"
)

// Node 包装 tsnet.Server：启动、状态汇总、节点列表缓存、登录与退出。
type Node struct {
	srv *tsnet.Server
	lcp atomic.Pointer[local.Client]

	running atomic.Bool
	suffix  atomic.Value // string

	mu          sync.Mutex
	peers       []Peer
	peersAt     time.Time
	peersDirty  bool
	lastSummary StatusSummary
	startErr    string
}

const peerCacheTTL = 10 * time.Second

func NewNode(opts *StartOptions) *Node {
	srv := &tsnet.Server{
		Dir:        opts.StateDir,
		Hostname:   opts.Hostname,
		ControlURL: opts.ControlURL,
		AuthKey:    opts.AuthKey,
		Ephemeral:  opts.Ephemeral,
		// tsnet 的调试日志与面向用户的输出（含登录链接）都不写到 stdout/stderr；登录链接经状态事件传给父进程。
		Logf:     logger.Discard,
		UserLogf: logger.Discard,
	}
	n := &Node{srv: srv}
	n.suffix.Store("")
	return n
}

// Start 启动 tsnet（不等待登录），随后在后台监听状态变化并经 emit 推送。
func (n *Node) Start(ctx context.Context, emit func(StatusSummary)) error {
	if err := n.srv.Start(); err != nil {
		n.mu.Lock()
		n.startErr = err.Error()
		n.mu.Unlock()
		return err
	}
	lc, err := n.srv.LocalClient()
	if err != nil {
		return err
	}
	n.lcp.Store(lc)
	go n.watch(ctx, lc, emit)
	return nil
}

func (n *Node) watch(ctx context.Context, lc *local.Client, emit func(StatusSummary)) {
	for ctx.Err() == nil {
		watcher, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyPeerChanges)
		if err != nil {
			if !sleepCtx(ctx, time.Second) {
				return
			}
			continue
		}
		for {
			if _, err := watcher.Next(); err != nil {
				break
			}
			n.mu.Lock()
			n.peersDirty = true
			n.mu.Unlock()
			n.publish(ctx, emit)
		}
		watcher.Close()
		if !sleepCtx(ctx, time.Second) {
			return
		}
	}
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

// publish 汇总状态，与上次不同时推送。
func (n *Node) publish(ctx context.Context, emit func(StatusSummary)) {
	summary, err := n.Summary(ctx)
	if err != nil {
		return
	}
	n.mu.Lock()
	changed := !summaryEqual(summary, n.lastSummary)
	n.lastSummary = summary
	n.mu.Unlock()
	if changed {
		emit(summary)
	}
}

func summaryEqual(a, b StatusSummary) bool {
	return a.BackendState == b.BackendState && a.AuthURL == b.AuthURL && a.SelfName == b.SelfName &&
		a.SelfDNSName == b.SelfDNSName && strings.Join(a.SelfIPs, ",") == strings.Join(b.SelfIPs, ",") &&
		a.Online == b.Online && a.MagicDNSSuffix == b.MagicDNSSuffix && a.TailnetName == b.TailnetName &&
		strings.Join(a.Health, "\n") == strings.Join(b.Health, "\n") && a.Error == b.Error
}

// Summary 读取不含节点列表的状态。
func (n *Node) Summary(ctx context.Context) (StatusSummary, error) {
	lc := n.lcp.Load()
	if lc == nil {
		n.mu.Lock()
		defer n.mu.Unlock()
		return StatusSummary{BackendState: "NoState", Error: n.startErr}, nil
	}
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		return StatusSummary{}, err
	}
	return n.summarize(st), nil
}

func (n *Node) summarize(st *ipnstate.Status) StatusSummary {
	s := StatusSummary{BackendState: st.BackendState, AuthURL: st.AuthURL, Health: st.Health}
	for _, ip := range st.TailscaleIPs {
		s.SelfIPs = append(s.SelfIPs, ip.String())
	}
	if st.Self != nil {
		s.SelfDNSName = strings.TrimSuffix(st.Self.DNSName, ".")
		s.SelfName = shortName(st.Self.DNSName, st.Self.HostName)
		s.Online = st.Self.Online
	}
	if st.CurrentTailnet != nil {
		s.MagicDNSSuffix = strings.TrimSuffix(st.CurrentTailnet.MagicDNSSuffix, ".")
		s.TailnetName = st.CurrentTailnet.Name
	} else {
		s.MagicDNSSuffix = strings.TrimSuffix(st.MagicDNSSuffix, ".")
	}
	running := st.BackendState == ipn.Running.String()
	n.running.Store(running)
	n.suffix.Store(s.MagicDNSSuffix)
	if running {
		// 在线后不再展示过期的登录链接。
		s.AuthURL = ""
	}
	return s
}

func shortName(dnsName, hostName string) string {
	if name := strings.TrimSuffix(dnsName, "."); name != "" {
		if i := strings.IndexByte(name, '.'); i > 0 {
			return name[:i]
		}
		return name
	}
	return hostName
}

// Running 实现 Directory。
func (n *Node) Running() bool { return n.running.Load() }

// MagicDNSSuffix 实现 Directory。
func (n *Node) MagicDNSSuffix() string { return n.suffix.Load().(string) }

// Peers 实现 Directory：缓存 peerCacheTTL，状态变化后失效。
func (n *Node) Peers(ctx context.Context, refresh bool) []Peer {
	n.mu.Lock()
	if !refresh && !n.peersDirty && time.Since(n.peersAt) < peerCacheTTL {
		peers := n.peers
		n.mu.Unlock()
		return peers
	}
	n.mu.Unlock()
	lc := n.lcp.Load()
	if lc == nil {
		return nil
	}
	st, err := lc.Status(ctx)
	if err != nil {
		// 强制刷新用于在线校验；失败时不允许使用旧缓存确认设备在线。
		if refresh {
			return nil
		}
		n.mu.Lock()
		defer n.mu.Unlock()
		return n.peers
	}
	n.summarize(st)
	var dm *tailcfg.DERPMap
	for _, ps := range st.Peer {
		if peerConnectionPath(ps, nil).path == pathDERP {
			dm, _ = lc.CurrentDERPMap(ctx)
			break
		}
	}
	peers := make([]Peer, 0, len(st.Peer))
	for _, ps := range st.Peer {
		if ps == nil {
			continue
		}
		path := peerConnectionPath(ps, dm)
		peer := Peer{
			DNSName:         normalizeHost(ps.DNSName),
			HostName:        normalizeHost(ps.HostName),
			OS:              ps.OS,
			Online:          ps.Online,
			Path:            path.path,
			DirectLAN:       path.lan,
			RelayRegion:     path.region,
			RelayRegionName: path.regionName,
		}
		peer.ShortName = normalizeHost(shortName(ps.DNSName, ps.HostName))
		for _, ip := range ps.TailscaleIPs {
			peer.IPs = append(peer.IPs, ip.Unmap())
		}
		peers = append(peers, peer)
	}
	sort.Slice(peers, func(i, j int) bool { return peers[i].ShortName < peers[j].ShortName })
	n.mu.Lock()
	n.peers, n.peersAt, n.peersDirty = peers, time.Now(), false
	n.mu.Unlock()
	return peers
}

// PeerList 返回供父进程展示的节点列表。
func (n *Node) PeerList(ctx context.Context) []PeerInfo {
	peers := n.Peers(ctx, true)
	out := make([]PeerInfo, 0, len(peers))
	for i := range peers {
		p := peers[i]
		info := PeerInfo{Name: p.ShortName, DNSName: p.DNSName, OS: p.OS, Online: p.Online}
		info.Path, info.DirectLAN = p.Path, p.DirectLAN
		info.RelayRegion, info.RelayRegionName = p.RelayRegion, p.RelayRegionName
		for _, ip := range p.IPs {
			info.IPs = append(info.IPs, ip.String())
		}
		if info.IPs == nil {
			info.IPs = []string{}
		}
		out = append(out, info)
	}
	return out
}

// Login 发起浏览器登录；登录链接随后经状态事件推送。
func (n *Node) Login(ctx context.Context) error {
	lc := n.lcp.Load()
	if lc == nil {
		return errNotStarted
	}
	return lc.StartLoginInteractive(ctx)
}

// Logout 注销本机节点；状态目录保留，下次登录生成新节点。
func (n *Node) Logout(ctx context.Context) error {
	lc := n.lcp.Load()
	if lc == nil {
		return errNotStarted
	}
	return lc.Logout(ctx)
}

// Dial 经 tsnet 拨号 tailnet 地址。
func (n *Node) Dial(ctx context.Context, network, address string) (net.Conn, error) {
	return n.srv.Dial(ctx, network, address)
}

// Close 关闭 tsnet。
func (n *Node) Close() error { return n.srv.Close() }

var errNotStarted = errors.New("tailnet 节点尚未启动")
