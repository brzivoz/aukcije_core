package rs.sud.eaukcija.refresh;

public class RefreshUnavailableException extends RuntimeException {

    public RefreshUnavailableException() {
        super("durable refresh is unavailable");
    }
}
