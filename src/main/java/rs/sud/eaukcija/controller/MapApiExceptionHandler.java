package rs.sud.eaukcija.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import rs.sud.eaukcija.map.InvalidMapRequestException;

/** Shared field-specific errors; the legacy code is retained for API compatibility. */
@RestControllerAdvice(assignableTypes = {MapAuctionController.class, AuctionController.class, AuctionViewController.class, AuctionReviewController.class})
@Profile("!local-h2")
public class MapApiExceptionHandler {

    @ExceptionHandler(rs.sud.eaukcija.history.SourceHistoryService.BoundaryException.class)
    ProblemDetail invalidBoundary(rs.sud.eaukcija.history.SourceHistoryService.BoundaryException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.code());
        problem.setProperty("code", exception.code());
        return problem;
    }

    @ExceptionHandler(InvalidMapRequestException.class)
    ProblemDetail invalidRequest(InvalidMapRequestException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid map request");
        problem.setProperty("code", "INVALID_MAP_REQUEST");
        problem.setProperty("field", exception.field());
        return problem;
    }
}
