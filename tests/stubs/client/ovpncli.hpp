#pragma once
#include <string>
#include <memory>
#include "openvpn/client/dns_options.hpp"
namespace openvpn_io {namespace detail {using socket_type=int;}}
namespace openvpn {namespace ClientAPI {
struct Event {bool error=false;std::string name;};
struct LogInfo {};
struct AppCustomControlMessageEvent {};
struct ExternalPKICertRequest {bool error=false; std::string errorText;};
struct ExternalPKISignRequest {bool error=false; std::string errorText;};
struct Config {std::string content,guiVersion; bool tunPersist=false,enableLegacyAlgorithms=false,enableNonPreferredDCAlgorithms=false;};
struct EvalConfig {bool error=false,autologin=true;};
struct ProvideCreds {std::string username,password;};
struct Status {bool error=false;};
class OpenVPNClient {
public:
 virtual bool pause_on_connection_timeout() = 0;
 virtual ~OpenVPNClient()=default;
 virtual bool socket_protect(openvpn_io::detail::socket_type,std::string,bool){return false;}
 virtual bool tun_builder_new(){return false;}
 virtual bool tun_builder_set_layer(int){return false;}
 virtual bool tun_builder_set_remote_address(const std::string&,bool){return false;}
 virtual bool tun_builder_add_address(const std::string&,int,const std::string&,bool,bool){return false;}
 virtual bool tun_builder_add_route(const std::string&,int,int,bool){return false;}
 virtual bool tun_builder_exclude_route(const std::string&,int,int,bool){return false;}
 virtual bool tun_builder_reroute_gw(bool,bool,unsigned int){return false;}
 virtual bool tun_builder_set_dns_options(const openvpn::DnsOptions&){return false;}
 virtual bool tun_builder_set_mtu(int){return false;}
 virtual bool tun_builder_set_session_name(const std::string&){return false;}
 virtual bool tun_builder_persist(){return false;}
 virtual int tun_builder_establish(){return -1;}
 virtual void tun_builder_teardown(bool){}
 virtual void event(const Event&)=0;
 virtual void acc_event(const AppCustomControlMessageEvent&)=0;
 virtual void log(const LogInfo&)=0;
 virtual void external_pki_cert_request(ExternalPKICertRequest&)=0;
 virtual void external_pki_sign_request(ExternalPKISignRequest&)=0;
 EvalConfig eval_config(const Config&){return {};}
 Status provide_creds(const ProvideCreds&){return {};}
 Status connect(){return {};}
 void stop(){}
};
}}
