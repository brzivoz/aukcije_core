package rs.sud.eaukcija.operations;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

@Controller
@Profile("!local-h2")
public class OperatorStatusPageController {

    @GetMapping("/operator/status")
    public String page(HttpServletRequest request, HttpServletResponse response) {
        if (!LoopbackRequest.isLoopback(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
        return "operator-status";
    }
}
