package rs.sud.eaukcija.operations;

import java.net.InetAddress;
import java.net.UnknownHostException;

import jakarta.servlet.http.HttpServletRequest;

final class LoopbackRequest {

    private LoopbackRequest() {
    }

    static boolean isLoopback(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException invalid) {
            return false;
        }
    }
}
