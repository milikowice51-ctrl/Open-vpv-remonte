#pragma once
#include <string>
#include <map>
#include <vector>
namespace openvpn {
struct DnsAddress { std::string address; unsigned port = 0; };
struct DnsDomain { std::string domain; };
struct DnsServer {
 enum class Transport { Unset, Plain, HTTPS, TLS };
 Transport transport = Transport::Unset;
 std::vector<DnsAddress> addresses;
};
struct DnsOptions {
 std::map<int,DnsServer> servers;
 std::vector<DnsDomain> search_domains;
};
}
