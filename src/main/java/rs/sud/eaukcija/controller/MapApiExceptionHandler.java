package rs.sud.eaukcija.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import rs.sud.eaukcija.map.InvalidMapRequestException;

/** Stable JSON errors for every client-correctable map request. */
@RestControllerAdvice(assignableTypes = MapAuctionController.class)
@Profile("!local-h2")
public class MapApiExceptionHandler {

    @ExceptionHandler(InvalidMapRequestException.class)
    ProblemDetail invalidRequest(InvalidMapRequestException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid map request");
        problem.setProperty("code", "INVALID_MAP_REQUEST");
        problem.setProperty("field", exception.field());
        return problem;
    }
}
