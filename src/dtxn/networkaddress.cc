// Copyright 2008,2009,2010 Massachusetts Institute of Technology.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

#include "networkaddress.h"

#include <netdb.h>

#include <cassert>
#include <cstdlib>
#include <cstring>

#include "base/assert.h"
#include "base/cast.h"
#include "base/stringutil.h"
#include "strings/utils.h"

using std::string;
using std::vector;

// disable -Wconversion to work around a bug in htons in glibc
// TODO: Remove this eventually
#if defined(__OPTIMIZE__) && __GNUC__ == 4 && __GNUC_MINOR__ >= 3
#pragma GCC diagnostic ignored "-Wconversion"
#endif

// Returns true if the address is parsed successfully.
bool NetworkAddress::parse(const std::string& address) {
    vector<string> parts = strings::splitExcluding(address, ' ');
    if (parts.size() == 1) {
        // Try splitting with a colon
        parts = strings::splitExcluding(address, ':');
    }
    if (parts.size() != 2) return false;
    if (parts[0].empty() || parts[1].empty()) return false;

    // Convert the first part from text to an IP address

    addrinfo* node = NULL;
    int error = getaddrinfo(parts[0].c_str(), NULL, NULL, &node);
    ASSERT(error == 0);

    bool found = false;
    addrinfo* ptr = node;
    while (ptr != NULL) {
        if (ptr->ai_family == AF_INET) {
            // Avoid alignment warning on Sparc
            const void* v = ptr->ai_addr;
            const sockaddr_in* addr = reinterpret_cast<const sockaddr_in*>(v);
            ip_address_ = addr->sin_addr.s_addr;
            found = true;
            break;
        }
        ptr = ptr->ai_next;
    }
    freeaddrinfo(node);

    if (!found) return false;

    // Convert the second part from an integer to a port number
    long int parsed_port = strtol(parts[1].c_str(), NULL, 10);
    if (! (0 < parsed_port && parsed_port < (1 << 16))) return false;
    port_ = htons(assert_range_cast<uint16_t>(parsed_port));
    return true;
}

bool NetworkAddress::operator==(const sockaddr_in& other) const {
    if (other.sin_family != AF_INET) return false;
    if (other.sin_port != port_) return false;
    if (other.sin_addr.s_addr != ip_address_) return false;
    return true;
}

static const int MAX_PORT_STRING = 6;  // maximum length: 6 bytes (5 digits, 1 NUL)
static std::string callGetNameInfo(const NetworkAddress& address, char* port_string) {
    sockaddr_in addr;
    address.fill(&addr);

    string retval;
    // maximum length for IPv4 address is 16 (12 digits, 3 '.', 1 NUL)
    // IPv6 is 16 bytes = 32 hex bytes + 7 ':' + NULL = 40
    retval.resize(40);
    int error = getnameinfo(reinterpret_cast<struct sockaddr*>(&addr),
            sizeof(addr), base::stringArray(&retval), static_cast<socklen_t>(retval.size()),
            port_string, MAX_PORT_STRING, NI_NUMERICHOST | NI_NUMERICSERV);
    ASSERT(error == 0);
    retval.resize(strlen(retval.data()));

    return retval;
}

string NetworkAddress::toString() const {
    char port_string[MAX_PORT_STRING];
    string retval = callGetNameInfo(*this, port_string);
    retval.push_back(':');
    retval.append(port_string);

    return retval;
}

string NetworkAddress::ipToString() const {
    char port_string[MAX_PORT_STRING];
    return callGetNameInfo(*this, port_string);
}

void NetworkAddress::fill(struct sockaddr_in* addr) const {
    addr->sin_family = AF_INET;
    addr->sin_port = port_;
    addr->sin_addr.s_addr = ip_address_;
    memset(addr->sin_zero, 0, sizeof(addr->sin_zero));
}

sockaddr_in NetworkAddress::sockaddr() const {
    sockaddr_in addr;
    fill(&addr);
    return addr;
}

uint16_t NetworkAddress::port() const {
    return ntohs(port_);
}
