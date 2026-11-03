package main

import (
	"net/netip"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
)

const (
	pathDirect    = "direct"
	pathPeerRelay = "peer_relay"
	pathDERP      = "derp"
)

// endpointIsLAN 只返回地址分类，原始 endpoint 不进入父进程协议。
func endpointIsLAN(endpoint string) bool {
	ap, err := netip.ParseAddrPort(endpoint)
	if err != nil {
		return false
	}
	ip := ap.Addr().Unmap()
	return ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLoopback()
}

func derpRegionName(dm *tailcfg.DERPMap, code string) string {
	if dm != nil && code != "" {
		for _, region := range dm.Regions {
			if region != nil && region.RegionCode == code {
				return region.RegionName
			}
		}
	}
	return ""
}

type connectionPath struct {
	path, region, regionName string
	lan                      bool
}

func peerConnectionPath(ps *ipnstate.PeerStatus, dm *tailcfg.DERPMap) connectionPath {
	if ps == nil || !ps.Online {
		return connectionPath{}
	}
	switch {
	case ps.CurAddr != "":
		return connectionPath{path: pathDirect, lan: endpointIsLAN(ps.CurAddr)}
	case ps.PeerRelay != "":
		return connectionPath{path: pathPeerRelay}
	case ps.Active && ps.Relay != "":
		return connectionPath{path: pathDERP, region: ps.Relay, regionName: derpRegionName(dm, ps.Relay)}
	default:
		return connectionPath{}
	}
}
